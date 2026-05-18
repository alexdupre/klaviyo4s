package com.alexdupre.klaviyo.codegen.emit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

/** Writes a sequence of [[EmittedFile]] to a single source tree.
  *
  * Layout under `rootDir`:
  *
  * {{{
  *   <rootDir>/src/main/scala/com/alexdupre/klaviyo4s/
  *     package.scala                        # top-level exports
  *     models/<TypeName>.scala              # flat DTO directory
  *     <category>/<Category>Api.scala       # per-tag API class
  *     <category>/Extensions.scala          # per-tag extension methods
  * }}}
  *
  * The writer overwrites every emitted file and removes any orphaned
  * `.scala` file under `src/main/scala/com/alexdupre/klaviyo4s/` that
  * we did NOT emit this run — so a spec refresh that drops a type or
  * category cleans up after itself. Non-`.scala` files (build markers,
  * resources, etc.) are left untouched.
  */
object Writer {

  /** Materialise `files` under `<rootDir>/<basePackage as path>/`,
    * removing orphaned `.scala` files in the same tree.
    *
    * @param rootDir     the directory the package root sits under
    *                    (for sbt source generators this is
    *                    `(Compile / sourceManaged).value / "klaviyo4s"`)
    * @param basePackage the Scala package the generated tree lives in;
    *                    becomes a slash-separated path under `rootDir`
    * @return absolute paths of every file written this run
    */
  def write(rootDir: Path, files: Seq[EmittedFile], basePackage: String = "com.alexdupre.klaviyo"): Seq[Path] = {
    // Fail fast on duplicate output paths. Two EmittedFile entries with
    // the same relative path would overwrite each other silently — the
    // last writer wins, and `pruneOrphans` won't notice because both
    // share the same final path. Surface the collision so it gets
    // fixed in the planner (typically a synthesised name clash).
    val collisions =
      files.groupBy(_.relativePath).filter(_._2.size > 1).keys.toList.sorted
    if (collisions.nonEmpty) {
      throw new IllegalStateException(
        "Duplicate emitted file paths — the planner produced two types resolving " +
          s"to the same output file. Offending paths:\n${collisions.map("  " + _).mkString("\n")}"
      )
    }
    val pkgRoot = basePackage.split('.').foldLeft(rootDir)(_.resolve(_))
    Files.createDirectories(pkgRoot)

    val written: Set[Path] = files.iterator.map { f =>
      val target = pkgRoot.resolve(f.relativePath).toAbsolutePath.normalize()
      Files.createDirectories(target.getParent)
      Files.writeString(target, f.content, StandardCharsets.UTF_8)
      target
    }.toSet

    pruneOrphans(pkgRoot, written)

    written.toSeq.sortBy(_.toString)
  }

  /** Remove `.scala` files under `pkgRoot` that we did not write this
    * run.
    */
  private def pruneOrphans(pkgRoot: Path, kept: Set[Path]): Unit = {
    if (!Files.exists(pkgRoot)) return
    val walker = Files.walk(pkgRoot)
    try {
      walker.iterator.asScala.toList.foreach { p =>
        if (Files.isRegularFile(p) && p.toString.endsWith(".scala") && !kept.contains(p.toAbsolutePath.normalize())) {
          Files.deleteIfExists(p)
        }
      }
    } finally walker.close()
  }
}
