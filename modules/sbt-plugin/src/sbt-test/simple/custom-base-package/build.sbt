ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "custom-base-package-test",
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.12" % Provided,
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_.startsWith("-Wunused")),
    klaviyoUseScalafmt := false,
    klaviyoBasePackage := "com.example.kv"
  )

TaskKey[Unit]("verifyCustomLayout") := {
  val srcManaged   = (Compile / sourceManaged).value / "klaviyo4s"
  val customRoot   = srcManaged / "com" / "example" / "kv"
  val accountsApi  = customRoot / "api" / "accounts" / "AccountsApi.scala"
  val pkgFile      = customRoot / "package.scala"
  val legacyRoot   = srcManaged / "com" / "alexdupre" / "klaviyo"
  assert(accountsApi.exists(), s"missing AccountsApi.scala at $accountsApi")
  assert(pkgFile.exists(),     s"missing root package.scala at $pkgFile")
  assert(!legacyRoot.exists(), s"unexpected legacy package tree at $legacyRoot")
  // Sanity-check the emitted package declaration honors the override.
  val pkgContent = IO.read(pkgFile)
  assert(pkgContent.contains("package com.example.kv"), s"package.scala does not declare com.example.kv:\n$pkgContent")
  streams.value.log.info(s"OK: generated tree honors klaviyoBasePackage at $customRoot")
}
