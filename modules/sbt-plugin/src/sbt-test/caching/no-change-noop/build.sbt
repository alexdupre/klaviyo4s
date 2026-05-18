ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "no-change-noop-test",
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
  streams.value.log.info(s"Snapshot: ${src.lastModified()} -> $dst")
}

TaskKey[Unit]("verifyNoRegen") := {
  val src      = (Compile / sourceManaged).value / "klaviyo4s" / "com" / "alexdupre" / "klaviyo" / "api" / "accounts" / "AccountsApi.scala"
  val snap     = target.value / "snapshots" / "AccountsApi.snapshot"
  assert(src.exists(),  s"src missing: $src")
  assert(snap.exists(), s"snapshot missing: $snap")
  val before = snap.lastModified()
  val after  = src.lastModified()
  assert(
    after == before,
    s"AccountsApi was regenerated despite no spec change " +
    s"(before=$before, after=$after)"
  )
  streams.value.log.info("OK: second compile skipped regeneration (same mtime)")
}
