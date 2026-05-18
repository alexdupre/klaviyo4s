package com.alexdupre.klaviyo.codegen.emit

import com.alexdupre.klaviyo.codegen.model.Planner
import com.alexdupre.klaviyo.codegen.spec.Parser

import java.nio.file.Paths

/** Run with `sbt 'codegen/Test/runMain ...PreviewMain'` to dump the
  * emitted output for `accounts.json` to stdout. Useful for eyeballing
  * the generator's output while iterating on the emitter — not part
  * of the test suite.
  */
object PreviewMain {
  def main(args: Array[String]): Unit = {
    val raw  = Parser.parseFile(Paths.get("specs/accounts.json"))
    val plan = Planner.plan(raw)
    Emitter.emit(plan).foreach { f =>
      println(s"=== ${f.relativePath} " + ("=" * (60 - f.relativePath.length)))
      println(f.content)
      println()
    }
  }
}
