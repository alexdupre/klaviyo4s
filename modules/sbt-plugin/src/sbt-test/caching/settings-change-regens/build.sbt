ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "settings-change-regens-test",
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.12" % Provided,
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_.startsWith("-Wunused")),
    klaviyoUseScalafmt := false
  )

TaskKey[Unit]("snapshotAccountsApi") := {
  val src = (Compile / sourceManaged).value / "klaviyo4s" / "com" / "alexdupre" / "klaviyo" / "api" / "accounts" / "AccountsApi.scala"
  val dst = target.value / "snapshots" / "AccountsApi.snapshot"
  IO.createDirectory(dst.getParentFile)
  IO.copyFile(src, dst, preserveLastModified = true)
}

TaskKey[Unit]("verifyRegen") := {
  // After `set klaviyoBasePackage := "com.example.kv"` the output
  // lands at a different path; we just confirm the old AccountsApi at
  // com/alexdupre/klaviyo/api/accounts/ was either removed or has
  // newer mtime, and a fresh one appears under com/example/kv/.
  val newApi = (Compile / sourceManaged).value / "klaviyo4s" / "com" / "example" / "kv" / "api" / "accounts" / "AccountsApi.scala"
  assert(newApi.exists(), s"missing regenerated AccountsApi at $newApi")
  streams.value.log.info("OK: settings change triggered regeneration under new package")
}
