package com.alexdupre.klaviyo.codegen

import com.alexdupre.klaviyo.codegen.emit.{Emitter, Writer}
import com.alexdupre.klaviyo.codegen.model.Planner
import com.alexdupre.klaviyo.codegen.spec.Parser

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalafmt.dynamic.coursier.CoursierDependencyDownloaderFactory
import org.scalafmt.interfaces.Scalafmt

/** End-to-end entry point for the codegen.
  *
  * The codegen has three callers in the published artefact world:
  *
  *   - `sbt-klaviyo4s`, the sbt plugin, calls [[run]] from a
  *     `sourceGenerators` task.
  *   - The `examples` module also enables the plugin, so it goes
  *     through the same path.
  *   - The local developer runs the codegen from sbt as
  *     `sbt 'codegen/runMain com.alexdupre.klaviyo.codegen.Generate'`,
  *     which goes through [[main]] below.
  *
  * The plan layer (`spec.Parser` → `model.Planner`) is pure; this
  * module is the only file in the codegen that performs I/O against
  * the filesystem.
  */
object Generate {

  /** Default spec file used by the CLI entry point. */
  val DefaultSpec: Path = Paths.get("specs/stable.json")

  /** Default output root used by the CLI entry point. The CLI writes
    * under `<rootDir>/src/main/scala/<basePackage as path>/` so the
    * historical `generated/src/main/scala/...` layout is preserved.
    *
    * The sbt plugin (which calls [[run]] directly) sets `outputDir`
    * to `(Compile / sourceManaged).value / "klaviyo4s"` and writes
    * generated sources straight under that directory.
    */
  val DefaultOutputDir: Path = Paths.get("generated/src/main/scala")

  /** Default base package — what the published runtime artefact
    * (`klaviyo4s-core`) uses, and what every example and scripted
    * test assumes unless explicitly overridden.
    */
  val DefaultBasePackage: String = "com.alexdupre.klaviyo"

  /** Configuration for a single end-to-end codegen run.
    *
    * @param specFile     path to the Klaviyo combined OpenAPI spec
    *                     (`stable.json`)
    * @param outputDir    package-root parent directory. The package
    *                     path derived from `basePackage` is created
    *                     under this directory and `.scala` files are
    *                     written into it.
    * @param basePackage  Scala root package for the generated tree.
    *                     Defaults to [[DefaultBasePackage]]. Used as
    *                     both the package declaration in emitted
    *                     files AND as the on-disk directory path
    *                     under `outputDir`.
    * @param useScalafmt  if true, run scalafmt over every emitted
    *                     file. Uses the bundled scalafmt config
    *                     (`klaviyo-scalafmt.conf` resource). Default
    *                     is true.
    * @param tags         restrict the generated tree by operation
    *                     tag (e.g. `Set("Profiles", "Lists")`).
    *                     Empty (the default) means "no tag filter".
    * @param operationIds restrict the generated tree by raw spec
    *                     `operationId` (e.g.
    *                     `Set("get_lists", "create_event")`).
    *                     Empty (the default) means "no operationId
    *                     filter".
    *
    * `tags` and `operationIds` compose additively: an operation is
    * kept iff its tag is in `tags` OR its operationId is in
    * `operationIds`. When both are empty every operation is kept.
    * The generated DTOs are then pruned to the set reachable from
    * the surviving operations, which can drastically shrink the
    * artefact for projects that only consume a slice of the
    * Klaviyo API.
    */
  final case class Settings(
    specFile: Path,
    outputDir: Path,
    basePackage: String = DefaultBasePackage,
    useScalafmt: Boolean = true,
    tags: Set[String] = Set.empty,
    operationIds: Set[String] = Set.empty
  )

  /** Parse the spec, plan, emit, write, optionally scalafmt.
    *
    * Returns the list of absolute paths written so sbt's
    * `FileFunction.cached` can register them as outputs. Failures
    * surface as the original exception thrown by Parser, Planner,
    * Writer, or Scalafmt — none of them is swallowed.
    */
  def run(settings: Settings): Seq[Path] = {
    val raw = Parser.parseFile(settings.specFile)
    val plan = Planner.plan(raw, settings.tags, settings.operationIds)
    val files = Emitter.emit(plan, settings.basePackage)
    val written = Writer.write(settings.outputDir, files, settings.basePackage)
    if (settings.useScalafmt) formatAll(written)
    written
  }

  /** CLI entry. Reads the default paths or overrides from:
    *   - `--spec PATH`           (default `specs/stable.json`)
    *   - `--output-dir PATH`     (default `generated/src/main/scala`)
    *   - `--base-package STR`    (default `com.alexdupre.klaviyo`)
    *   - `--spec-version REF`    (Git ref to fetch, default `main`)
    *   - `--no-scalafmt`         (skip scalafmt pass)
    *   - `--fetch-if-missing`    (download `--spec` from github if it
    *                              is not on disk; uses `--spec-version`
    *                              as the ref)
    *   - `--tags A,B,C`          (restrict the generated tree to the
    *                              listed operation tags; default is
    *                              the empty set = every tag)
    *   - `--operation-ids X,Y`   (restrict the generated tree to the
    *                              listed `operationId`s; composes with
    *                              `--tags` as a union — operations
    *                              matching either filter are kept)
    */
  def main(args: Array[String]): Unit = {
    val flags = parseFlags(args.toList)
    val specPath = flags.get("spec").map(Paths.get(_)).getOrElse(DefaultSpec)
    val outputDir = flags.get("output-dir").map(Paths.get(_)).getOrElse(DefaultOutputDir)
    val basePackage = flags.getOrElse("base-package", DefaultBasePackage)
    val useScalafmt = !flags.contains("no-scalafmt")
    val fetchIfMissing = flags.contains("fetch-if-missing")
    val specVersion = flags.getOrElse("spec-version", spec.SpecDownloader.DefaultRef)
    val tags = flags.get("tags").fold(Set.empty[String]) { csv =>
      csv.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
    }
    val operationIds = flags.get("operation-ids").fold(Set.empty[String]) { csv =>
      csv.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
    }

    if (!Files.exists(specPath) && fetchIfMissing) {
      println(s"Spec missing — fetching klaviyo openapi @ $specVersion ...")
      spec.SpecDownloader.download(specVersion, specPath)
    }

    println(s"Reading $specPath ...")
    val raw = Parser.parseFile(specPath)
    println(s"  ${raw.components.schemas.size} schemas, ${raw.paths.size} paths")

    if (tags.nonEmpty) println(s"Tag filter: ${tags.toList.sorted.mkString(", ")}")
    if (operationIds.nonEmpty) println(s"OperationId filter: ${operationIds.toList.sorted.mkString(", ")}")
    println("Planning ...")
    val plan = Planner.plan(raw, tags, operationIds)
    println(
      s"  ${plan.types.size} types, ${plan.categories.size} categories, ${plan.categories.map(_.operations.size).sum} operations"
    )

    println(s"Emitting (base package $basePackage) ...")
    val files = Emitter.emit(plan, basePackage)
    val written = Writer.write(outputDir, files, basePackage)
    println(s"  wrote ${written.size} files")

    if (useScalafmt) {
      println("Formatting with scalafmt ...")
      formatAll(written)
      println("  done")
    }
  }

  // --------------------------------------------------------------------
  // Scalafmt
  // --------------------------------------------------------------------

  /** Cached scalafmt instance. Built once on first use; downstream
    * calls only pay the formatter cost. The bundled config always
    * sets `version = ...` so we don't need any extra opt-in here.
    *
    * scalafmt-dynamic 3.11.0 split the coursier-based dependency
    * downloader out of the main bundle and registered it via the
    * `ServiceLoader` SPI on `RepositoryPackageDownloaderFactory`. When
    * this codegen runs as a transitive dependency of `sbt-klaviyo4s`
    * inside an external user's build, sbt's plugin classloader may
    * not surface the `META-INF/services/...` resource at SPI lookup
    * time — the user then sees `failed to download` with a hint to
    * register the factory. We bypass the SPI entirely by injecting
    * the bundled `CoursierDependencyDownloaderFactory` directly via
    * `withRepositoryPackageDownloader`; the factory class is on our
    * own classpath because we depend on `scalafmt-dynamic` directly.
    */
  private lazy val scalafmtSession: org.scalafmt.interfaces.Scalafmt =
    Scalafmt
      .create(getClass.getClassLoader)
      .withRepositoryPackageDownloader(new CoursierDependencyDownloaderFactory())

  private lazy val bundledConfigPath: Path = {
    // The bundled config lives at modules/codegen/src/main/resources/klaviyo-scalafmt.conf
    // and ships in the codegen jar. We extract it to a temp file because
    // scalafmt-dynamic expects a real Path.
    val resource = getClass.getResourceAsStream("/klaviyo-scalafmt.conf")
    if (resource == null) {
      throw new IllegalStateException(
        "klaviyo-scalafmt.conf missing from classpath — packaging defect"
      )
    }
    try {
      val tmp = Files.createTempFile("klaviyo-scalafmt-", ".conf")
      val bytes = readAll(resource)
      Files.write(tmp, bytes)
      tmp.toFile.deleteOnExit()
      tmp
    } finally resource.close()
  }

  private def readAll(in: InputStream): Array[Byte] = {
    val buf = new java.io.ByteArrayOutputStream()
    val tmp = new Array[Byte](16384)
    var n = in.read(tmp)
    while (n != -1) {
      buf.write(tmp, 0, n)
      n = in.read(tmp)
    }
    buf.toByteArray
  }

  private def formatAll(files: Seq[Path]): Unit = {
    val cfg = bundledConfigPath
    files.foreach { p =>
      val original = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
      val formatted = scalafmtSession.format(cfg, p, original)
      Files.write(p, formatted.getBytes(StandardCharsets.UTF_8))
    }
  }

  // --------------------------------------------------------------------
  // CLI argv parsing
  // --------------------------------------------------------------------

  private def parseFlags(args: List[String]): Map[String, String] = {
    val out = scala.collection.mutable.Map.empty[String, String]
    var i = 0
    val arr = args.toIndexedSeq
    while (i < arr.size) {
      val a = arr(i)
      if (a == "--no-scalafmt") { out("no-scalafmt") = "true"; i += 1 }
      else if (a.startsWith("--") && i + 1 < arr.size) {
        out(a.stripPrefix("--")) = arr(i + 1)
        i += 2
      } else i += 1
    }
    out.toMap
  }
}
