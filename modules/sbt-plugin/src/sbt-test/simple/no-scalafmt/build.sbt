ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    name := "no-scalafmt-test",
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.12" % Provided,
    Compile / scalacOptions := (Compile / scalacOptions).value.filterNot(_.startsWith("-Wunused")),
    klaviyoUseScalafmt := false
  )

TaskKey[Unit]("verifyCompileOnly") := {
  // Just confirm the generated tree compiled by inspecting the class
  // output directory.
  val classes = (Compile / classDirectory).value
  val anyClass = (classes ** "*.class").get.headOption
  assert(anyClass.nonEmpty, s"no compiled classes under $classes")
  streams.value.log.info("OK: generated tree compiled without scalafmt")
}
