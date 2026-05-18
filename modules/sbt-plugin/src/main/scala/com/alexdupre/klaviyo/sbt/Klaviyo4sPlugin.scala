package com.alexdupre.klaviyo.sbt

import sbt._
import sbt.Keys._
import com.alexdupre.klaviyo.codegen.Generate
import com.alexdupre.klaviyo.codegen.spec.SpecDownloader

/** sbt plugin that generates a Klaviyo client from the upstream OpenAPI
  * spec into `src_managed`. The generated tree is wired into the
  * project's `Compile / sources` so it compiles alongside hand-written
  * code.
  *
  * Typical user setup:
  * {{{
  *   // project/plugins.sbt
  *   addSbtPlugin("com.alexdupre" % "sbt-klaviyo4s" % "x.y.z")
  *
  *   // build.sbt
  *   lazy val app = (project in file("."))
  *     .enablePlugins(Klaviyo4sPlugin)
  *     .settings(scalaVersion := "3.4.2")
  *
  *   // Optional overrides:
  *   klaviyoSpecVersion := "2026-04-15"   // pin to a Klaviyo release branch
  *   klaviyoBasePackage := "com.myco.kv"  // emit under a different package
  *   klaviyoUseScalafmt := false          // skip scalafmt pass
  * }}}
  *
  * The plugin auto-fetches the spec on first compile (or whenever the
  * configured `klaviyo-specs/stable.json` file is missing). Subsequent
  * compiles use the cached spec and re-run codegen only when the spec
  * contents, the plugin version, or any plugin setting changes.
  */
object Klaviyo4sPlugin extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {

    val klaviyoSpecVersion = settingKey[String](
      "Git ref of klaviyo/openapi to download. Defaults to \"main\" (latest). " +
        "Use a date-named branch (e.g. \"2026-04-15\") to pin a specific revision."
    )

    val klaviyoSpecsDir = settingKey[File](
      "Directory holding the downloaded klaviyo OpenAPI spec. " +
        "Default: <project>/klaviyo-specs. Commit the spec file to make builds reproducible."
    )

    val klaviyoBasePackage = settingKey[String](
      "Root Scala package for generated sources. Default: com.alexdupre.klaviyo."
    )

    val klaviyoUseScalafmt = settingKey[Boolean](
      "If true, scalafmt is run over generated sources after emission. Default: true."
    )

    val klaviyoTags = settingKey[Seq[String]](
      "Restrict the generated tree to the listed operation tags (e.g. " +
        "Seq(\"Profiles\", \"Lists\")). Default: empty = no tag filter. " +
        "Composes additively with `klaviyoOperationIds` — an operation " +
        "is kept iff its tag matches OR its operationId matches. When " +
        "both are empty, every operation is generated. Both Api classes " +
        "and DTOs are filtered down to what's reachable from the " +
        "surviving operations, which can drastically shrink the " +
        "generated artefact for projects that consume only a slice of " +
        "the Klaviyo API."
    )

    val klaviyoOperationIds = settingKey[Seq[String]](
      "Restrict the generated tree to the listed `operationId`s (e.g. " +
        "Seq(\"get_lists\", \"create_event\")). Default: empty = no " +
        "operationId filter. Composes additively with `klaviyoTags` " +
        "(union, not intersection) — useful for pulling a single " +
        "extra operation from a different category without unlocking " +
        "its whole tag set."
    )

    val klaviyoRefreshSpecs = taskKey[File](
      "Download the Klaviyo OpenAPI spec at `klaviyoSpecVersion` into `klaviyoSpecsDir`."
    )

    val klaviyoGenerate = taskKey[Seq[File]](
      "Generate Klaviyo client sources into src_managed. Cached: re-runs only when " +
        "the spec, the plugin version, or plugin settings change."
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    klaviyoSpecVersion := SpecDownloader.DefaultRef,
    klaviyoSpecsDir := baseDirectory.value / "klaviyo-specs",
    klaviyoBasePackage := Generate.DefaultBasePackage,
    klaviyoUseScalafmt := true,
    klaviyoTags := Seq.empty,
    klaviyoOperationIds := Seq.empty,

    klaviyoRefreshSpecs := {
      val log = streams.value.log
      val dir = klaviyoSpecsDir.value
      val ref = klaviyoSpecVersion.value
      val dest = (dir / "stable.json").toPath
      log.info(s"Fetching Klaviyo OpenAPI spec @ $ref -> $dest")
      SpecDownloader.download(ref, dest)
      dest.toFile
    },

    // Dynamic: only depend on `klaviyoRefreshSpecs` when the cached
    // spec is actually missing. Avoids re-downloading on every build.
    //
    // The user-tunable settings (`klaviyoBasePackage`, `klaviyoUseScalafmt`,
    // `klaviyoTags`, `klaviyoOperationIds`) are read at this outer
    // task level — not inside `generateFromSpec` — so sbt's
    // `lintUnused` check can see the dependency. Without the
    // explicit `.value` here, settings consumed only through the
    // private helper appear unused to lint and the user gets a
    // noisy warning the first time they override them.
    klaviyoGenerate := Def.taskDyn {
      val cachedSpec = klaviyoSpecsDir.value / "stable.json"
      val basePackage = klaviyoBasePackage.value
      val useScalafmt = klaviyoUseScalafmt.value
      val tags = klaviyoTags.value.toSet
      val operationIds = klaviyoOperationIds.value.toSet
      if (cachedSpec.exists()) generateFromSpec(cachedSpec, basePackage, useScalafmt, tags, operationIds)
      else {
        streams.value.log.info("Klaviyo spec not present locally — fetching.")
        Def.taskDyn(generateFromSpec(klaviyoRefreshSpecs.value, basePackage, useScalafmt, tags, operationIds))
      }
    }.value,

    Compile / sourceGenerators += klaviyoGenerate.taskValue,

    // The published runtime artefact. The sbt module name keeps the
    // historical `klaviyo4s-` prefix; only the Scala *package* root
    // is `com.alexdupre.klaviyo`.
    libraryDependencies += "com.alexdupre" %% "klaviyo4s-core" % BuildInfo.version
  )

  /** Inner generation task. Factored out so [[klaviyoGenerate]] can
    * depend on either the cached spec file or a freshly-fetched one
    * without duplicating the cache + `Generate.run` wiring.
    *
    * The plugin settings (`basePackage`, `useScalafmt`, `tags`,
    * `operationIds`) are passed in as plain values rather than
    * re-read here so sbt's `lintUnused` check, which traces `.value`
    * calls at the outer task level, can see the dependency on those
    * settings.
    */
  private def generateFromSpec(
    specFile: File,
    basePackage: String,
    useScalafmt: Boolean,
    tags: Set[String],
    operationIds: Set[String]
  ): Def.Initialize[Task[Seq[File]]] = Def.task {
    val log = streams.value.log
    val outDir = (Compile / sourceManaged).value / "klaviyo4s"
    // Inputs that must bust the cache: plugin version + every plugin
    // setting that influences output. Folded into a stable key.
    // Filter selections are sorted so reordering the user's
    // `klaviyoTags` / `klaviyoOperationIds` Seq doesn't accidentally
    // force a regen.
    val key = Seq(
      BuildInfo.version,
      basePackage,
      useScalafmt.toString,
      tags.toList.sorted.mkString(","),
      operationIds.toList.sorted.mkString(",")
    ).mkString("|")
    val cacheDir = streams.value.cacheDirectory / s"klaviyo-${key.hashCode.toHexString}"
    val cached: Set[File] => Set[File] = FileFunction.cached(
      cacheDir,
      inStyle = FilesInfo.hash,
      outStyle = FilesInfo.exists
    ) { (_: Set[File]) =>
      val tagSummary = if (tags.isEmpty) "(none)" else tags.toList.sorted.mkString(",")
      val opIdSummary = if (operationIds.isEmpty) "(none)" else operationIds.toList.sorted.mkString(",")
      val filterSummary =
        if (tags.isEmpty && operationIds.isEmpty) "all"
        else s"tags=$tagSummary, operationIds=$opIdSummary"
      log.info(
        s"Regenerating Klaviyo client into $outDir (basePackage=$basePackage, scalafmt=$useScalafmt, filter=$filterSummary)"
      )
      IO.createDirectory(outDir)
      Generate
        .run(Generate.Settings(
          specFile = specFile.toPath,
          outputDir = outDir.toPath,
          basePackage = basePackage,
          useScalafmt = useScalafmt,
          tags = tags,
          operationIds = operationIds
        ))
        .map(_.toFile)
        .toSet
    }
    cached(Set(specFile)).toSeq
  }
}
