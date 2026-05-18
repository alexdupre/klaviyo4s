// Plugins used by the klaviyo4s build.
//
// `sbt-scalafmt`     — formatting hand-written sources (generated code is
//                      formatted in-process by the codegen).
// `sbt-buildinfo`    — feeds the plugin's own version into the
//                      `Klaviyo4sPlugin` cache key so a plugin upgrade
//                      busts the per-project regeneration cache.
// `sbt-pgp`          — signs artifacts to be published to Sonatype
//                      repository.
//
// The codegen is NOT wired into the root `compile`. Generation runs from
// the user's build via `sbt-klaviyo4s`; the examples module dogfoods
// that plugin.

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
