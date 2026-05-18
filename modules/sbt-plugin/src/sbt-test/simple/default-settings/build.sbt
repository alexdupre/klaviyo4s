ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "default-settings-test",
    // The plugin auto-adds klaviyo4s-core; sourceGenerator wires the
    // generated tree into Compile. Just compile + assert the package
    // landed under the expected path.
    //
    // Deliberately leaves `klaviyoUseScalafmt` at its default (`true`)
    // so this test exercises the scalafmt code path end-to-end. We hit
    // a regression when scalafmt-dynamic 3.11.0 moved its coursier
    // downloader behind an SPI that didn't auto-register from the
    // plugin classloader; that failure mode only surfaces when the
    // formatter actually runs, so at least one scripted test needs to
    // exercise it. The other scripted tests still pin
    // `klaviyoUseScalafmt := false` to stay offline / fast.
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.12" % Provided,
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_.startsWith("-Wunused"))
  )

TaskKey[Unit]("verifyDefaultLayout") := {
  val srcManaged   = (Compile / sourceManaged).value / "klaviyo4s"
  val accountsApi  = srcManaged / "com" / "alexdupre" / "klaviyo" / "api" / "accounts" / "AccountsApi.scala"
  val modelsDir    = srcManaged / "com" / "alexdupre" / "klaviyo" / "models"
  val pkgFile      = srcManaged / "com" / "alexdupre" / "klaviyo" / "package.scala"
  assert(accountsApi.exists(), s"missing AccountsApi.scala at $accountsApi")
  assert(modelsDir.isDirectory, s"missing models dir at $modelsDir")
  assert(pkgFile.exists(),     s"missing root package.scala at $pkgFile")
  streams.value.log.info(s"OK: generated tree at $srcManaged is well-formed")
}
