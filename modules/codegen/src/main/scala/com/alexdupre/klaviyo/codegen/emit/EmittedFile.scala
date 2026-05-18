package com.alexdupre.klaviyo.codegen.emit

/** A single source file produced by the code generator.
  *
  * `relativePath` is relative to the category's `src/main/scala`
  * directory (e.g. `accounts/models/AccountEnum.scala`). `content` is
  * the formatted Scala source — already pretty-printed by scala.meta
  * but not yet scalafmt-canonicalised; that runs as a separate sbt
  * step over the whole `generated/` directory.
  */
final case class EmittedFile(relativePath: String, content: String)
