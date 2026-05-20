// Multi-project build for klaviyo4s.
//
// Layout:
//   modules/core          -> klaviyo4s-core      (published runtime; types, codecs, KlaviyoClient, retry, pagination)
//   modules/codegen       -> klaviyo4s-codegen   (published build-time library, Scala 2.12; OpenAPI parser + scala.meta emitter)
//   modules/sbt-plugin    -> sbt-klaviyo4s       (published sbt 1.x plugin; consumes the codegen)
//   examples              -> examples            (NOT published; dogfoods codegen via inline sourceGenerator)
//
// Generated code lives under the user's `src_managed` (via the plugin) or
// the examples module's `src_managed` (via the inline `sourceGenerator`).
// The codegen drives off a single Klaviyo OpenAPI file (`stable.json`).
// All emitted DTOs land in `com.alexdupre.klaviyo.models.*`, per-tag Api
// classes in `com.alexdupre.klaviyo.api.<tag>.*` — one artefact per
// generated build, no per-category proliferation.

import sbt._
import Keys._
import sbtbuildinfo.BuildInfoKey

// --- Centralised dependency versions ----------------------------------------

/** Scala 3 compiler — runtime library + emitted code. */
lazy val scala3Version = "3.3.7"

/** Scala 2.12 — used by `klaviyo4s-codegen` and the sbt plugin. sbt 1.x runs
  * on Scala 2.12, so a publishable sbt-plugin module must target 2.12, and
  * the codegen library it depends on must cross-build to (or target) 2.12 too.
  */
lazy val scala212Version = "2.12.21"

/** jsoniter-scala (core + macros). Used everywhere we touch JSON. */
lazy val jsoniterVersion = "2.38.12"

/** sttp-client 4. Transport for the generated category clients. */
lazy val sttpVersion = "4.0.23"

/** scala.meta — AST construction for the code generator. */
lazy val scalametaVersion = "4.17.0"

/** scalafmt — formatter version, applied to `generated/` after each codegen
  * run via `scalafmt-dynamic`. Pins both the dynamic dependency and the
  * runner version the bundled `klaviyo-scalafmt.conf` requests.
  */
lazy val scalafmtVersion = "3.11.1"

/** munit — test framework. */
lazy val munitVersion = "1.3.0"

/** Project version. */
ThisBuild / version := "0.9.1"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / organization := "com.alexdupre"
ThisBuild / organizationName := "Alex Dupre"
ThisBuild / organizationHomepage := Some(url("https://github.com/alexdupre"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/alexdupre/klaviyo4s"),
    "scm:git:git@github.com:alexdupre/klaviyo4s.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "alexdupre",
    name = "Alex Dupre",
    email = "ale@FreeBSD.org",
    url = url("https://github.com/alexdupre")
  )
)

ThisBuild / description := "Scala 3 code generator for the Klaviyo API"
ThisBuild / licenses := Seq("BSD-2-Clause" -> url("https://opensource.org/license/bsd-2-clause"))
ThisBuild / homepage := Some(url("https://github.com/alexdupre/klaviyo4s"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// Tests touch shared mutable state in the SyncBackendStub (request-capture
// vars) and rely on deterministic file ordering on disk. Parallel execution
// is off across every module.
ThisBuild / Test / parallelExecution := false

// Common compiler flags. Kept conservative — `-explain` makes Scala 3 error
// messages much more useful; `-Wunused:all` and `-deprecation` are pure
// hygiene. We avoid `-Werror` so a Klaviyo spec refresh that surfaces a new
// deprecated field cannot break CI by itself.
//
// `-language:implicitConversions` is enabled so the `Tristate[A]` ergonomic
// conversion (raw `A` → `Tristate.Value(a)`) fires without forcing every
// caller to add a `scala.language` import. This is the only implicit
// conversion the library defines.
val commonSettings: Seq[Setting[?]] = Seq(
  scalaVersion := scala3Version,
  Compile / scalacOptions ++= Seq(
    "-explain",
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-Wunused:all"
  )
)

// Scala 2.12 settings. Used by `klaviyo4s-codegen` (publishable library)
// and the sbt plugin module.
val commonScala212Settings: Seq[Setting[?]] = Seq(
  scalaVersion := scala212Version,
  Compile / scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-Ywarn-unused"
  )
)

// --- Hand-written modules ----------------------------------------------------

/** klaviyo4s-core — the runtime module.
  *
  * Contains the shared types (Tristate, KlaviyoConfig, JSON:API
  * envelope, error model), jsoniter codecs, and the sttp-based runtime
  * (`KlaviyoClient[F]`, `Executor`, `Sleep[F]`, `Pagination`). Single
  * module so the generated `klaviyo4s` artefact only needs one runtime
  * dependency.
  */
lazy val core = (project in file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "klaviyo4s-core",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % Provided,
      "com.softwaremill.sttp.client4" %% "core" % sttpVersion,
      "com.softwaremill.sttp.client4" %% "jsoniter" % sttpVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    // sbt-buildinfo emits a generated `BuildInfo` object exposing the
    // module's `version` to runtime code. Used by `KlaviyoConfig` to
    // build the default User-Agent string so the value automatically
    // tracks the published library version.
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.alexdupre.klaviyo.core"
  )

/** klaviyo4s-codegen — publishable build-time library.
  *
  * Parses Klaviyo's combined `stable.json` OpenAPI spec and emits Scala 3
  * sources via scala.meta. **Targets Scala 2.12** so the sbt 1.x plugin
  * module (`sbt-klaviyo4s`) can depend on it directly. The codegen itself
  * does not run user code — only generates `.scala` source files — so its
  * language version is independent of the emitted output (which stays
  * Scala 3).
  *
  * `scalafmt-dynamic` is a 2.12-only artefact; pulling it in here was
  * the original reason the codegen had to flip from Scala 3 to 2.12.
  */
lazy val codegen = (project in file("modules/codegen"))
  .settings(commonScala212Settings)
  .settings(
    name := "klaviyo4s-codegen",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % Provided,
      "org.scalameta" %% "scalameta" % scalametaVersion,
      "org.scalameta" %% "scalafmt-dynamic" % scalafmtVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

// --- sbt plugin --------------------------------------------------------------

/** sbt-klaviyo4s — the published sbt 1.x plugin.
  *
  * Depends on `klaviyo4s-codegen` (Scala 2.12) and exposes typed
  * settings + tasks that wire codegen into a user project's
  * `Compile / sourceGenerators`. Carries a small `BuildInfo` object
  * with its own version so the regeneration cache key includes the
  * plugin version and a plugin upgrade busts the cache automatically.
  *
  * Scripted tests live under `modules/sbt-plugin/src/sbt-test/`.
  */
lazy val sbtPlugin212 = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin, BuildInfoPlugin)
  .dependsOn(codegen)
  .settings(commonScala212Settings)
  .settings(
    name := "sbt-klaviyo4s",
    sbtPlugin := true,
    pluginCrossBuild / sbtVersion := "1.10.7",
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.alexdupre.klaviyo.sbt",
    scriptedLaunchOpts := scriptedLaunchOpts.value ++
      Seq("-Xmx2G", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog := false,
    // Scripted tests resolve every dependency from local ivy. sbt's
    // default `scriptedDependencies` already publishLocals the
    // plugin itself, but NOT its module deps — we have to add
    // `core` and `codegen` explicitly or the scripted projects fail
    // to resolve `klaviyo4s-core` and `klaviyo4s-codegen`.
    scriptedDependencies :=
      scriptedDependencies
        .dependsOn(core / publishLocal, codegen / publishLocal)
        .value
  )

// --- Examples ----------------------------------------------------------------

// `examples` doubles as the integration-test home: it invokes the
// codegen at build time (matching the user instruction "generate the
// code directly from the codegen module"). It does NOT enable the sbt
// plugin — that path is validated by scripted tests under
// `modules/sbt-plugin/src/sbt-test/`. The two paths reach `Generate.run`
// the same way, so testing one tests the codegen contract for both.
//
// `-Wunused` is disabled because the codegen-generated tree under
// `src_managed/` produces uniform imports across all files; teaching
// the emitter to be import-minimal would add real complexity without
// buying anything but cleaner warnings.
lazy val examples = (project in file("examples"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "examples",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % Provided,
      "com.softwaremill.sttp.client4" %% "core" % sttpVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_.startsWith("-Wunused")),
    // Invoke the codegen as a normal sourceGenerator. The codegen lives
    // on Scala 2.12 (sbt's own version), so we spawn it through sbt's
    // `runner` infrastructure against the codegen project's full
    // classpath — this works across the Scala-3 / Scala-2.12 boundary
    // because we never link, only fork.
    //
    // The regeneration is **cached** via `FileFunction.cached` keyed
    // on:
    //   - the spec file's content hash (changes when you refresh from
    //     upstream via the plugin or by hand);
    //   - the codegen jar (rebuilt whenever you edit anything under
    //     `modules/codegen/`).
    // Result: running a demo when neither input changed re-uses the
    // 2389 files already under `src_managed`, instead of regenerating
    // and re-formatting them every time.
    //
    // The `--fetch-if-missing` flag lets the codegen pull the spec
    // from upstream on first build. When the spec doesn't yet exist
    // we can't include it in the cache inputs, so we run the codegen
    // once unconditionally; the next build picks up caching naturally.
    Compile / sourceGenerators += Def.task {
      val log = streams.value.log
      val outDir = (Compile / sourceManaged).value / "klaviyo4s"
      val specFile = baseDirectory.value / "klaviyo-specs" / "stable.json"
      val cp = (codegen / Compile / fullClasspath).value.map(_.data)
      val r = (codegen / Compile / runner).value
      // Force codegen compilation so the jar reflects current sources.
      // The packaged jar is then a stable, hashable artefact for the
      // cache key — an edit to any codegen source bumps it.
      val codegenJar = (codegen / Compile / packageBin).value
      val cacheDir = streams.value.cacheDirectory / "klaviyo-codegen"

      def runCodegen(): Unit = {
        log.info(s"Regenerating Klaviyo client into $outDir")
        IO.createDirectory(outDir)
        r.run(
          "com.alexdupre.klaviyo.codegen.Generate",
          cp,
          Seq(
            "--spec",
            specFile.getAbsolutePath,
            "--output-dir",
            outDir.getAbsolutePath,
            "--fetch-if-missing",
            "--no-scalafmt"
          ),
          log
        ).get
      }

      val cached: Set[File] => Set[File] = FileFunction.cached(
        cacheDir,
        inStyle = FilesInfo.hash,
        outStyle = FilesInfo.exists
      ) { (_: Set[File]) =>
        runCodegen()
        (outDir ** "*.scala").get.toSet
      }

      if (specFile.exists()) {
        cached(Set(specFile, codegenJar)).toSeq
      } else {
        log.info("Klaviyo spec not present locally; fetching + generating (cache will activate next build).")
        runCodegen()
        (outDir ** "*.scala").get
      }
    }.taskValue
  )

// --- Root aggregator ---------------------------------------------------------

lazy val root = (project in file("."))
  .aggregate(core, codegen, sbtPlugin212, examples)
  .settings(
    name := "klaviyo4s-build",
    publish / skip := true
  )
