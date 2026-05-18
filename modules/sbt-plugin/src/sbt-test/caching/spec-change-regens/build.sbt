ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "spec-change-regens-test",
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

TaskKey[Unit]("mutateSpec") := {
  // Append a no-op key to the spec. jsoniter ignores unknown top-level
  // fields so the parsed structure is identical, but the file *bytes*
  // change and `FileFunction.cached` keys on bytes -> regen.
  val spec = klaviyoSpecsDir.value / "stable.json"
  val text = IO.read(spec)
  val patched = text.replaceFirst("\\{", "{\n  \"x-test-mutation\": \"" + System.currentTimeMillis() + "\",")
  IO.write(spec, patched)
  streams.value.log.info(s"Mutated $spec")
}

TaskKey[Unit]("verifyRegen") := {
  val src  = (Compile / sourceManaged).value / "klaviyo4s" / "com" / "alexdupre" / "klaviyo" / "api" / "accounts" / "AccountsApi.scala"
  val snap = target.value / "snapshots" / "AccountsApi.snapshot"
  assert(src.exists(),  s"src missing: $src")
  assert(snap.exists(), s"snapshot missing: $snap")
  assert(
    src.lastModified() > snap.lastModified(),
    s"AccountsApi was NOT regenerated despite spec change " +
    s"(before=${snap.lastModified()}, after=${src.lastModified()})"
  )
  streams.value.log.info("OK: spec change triggered regeneration")
}
