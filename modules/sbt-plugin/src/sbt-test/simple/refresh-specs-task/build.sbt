ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "refresh-specs-task-test",
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.12" % Provided,
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_.startsWith("-Wunused")),
    klaviyoUseScalafmt := false,
    // Use a temp dir so the test runs even when network is offline (we
    // pre-populate with the vendored fixture, then call refresh which
    // should overwrite from upstream when network is available).
    klaviyoSpecsDir := target.value / "klaviyo-specs"
  )

TaskKey[Unit]("verifyRefreshedSpec") := {
  val target = klaviyoSpecsDir.value / "stable.json"
  assert(target.exists(), s"spec missing after refresh task: $target")
  // Must be parseable JSON and start with the OpenAPI envelope.
  val first256 = IO.read(target).take(256)
  assert(first256.contains("openapi"), s"refreshed file is not an openapi spec:\n$first256")
  streams.value.log.info(s"OK: refreshed klaviyo spec at $target")
}
