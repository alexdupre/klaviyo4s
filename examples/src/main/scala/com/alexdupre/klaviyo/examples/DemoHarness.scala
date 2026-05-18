package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import sttp.client4.DefaultSyncBackend
import sttp.shared.Identity

import java.util.UUID
import scala.util.{Failure, Success, Try}

/** Shared scaffolding for the per-category demo apps.
  *
  * Each demo extends [[DemoApp]] and implements [[DemoApp.runDemo]].
  * The harness:
  *
  *   - reads `KLAVIYO_API_KEY` from the environment; if unset, prints
  *     a friendly message and exits cleanly (so CI can run every demo
  *     without surprise failures);
  *   - constructs a synchronous `KlaviyoClient[Identity]` against
  *     `DefaultSyncBackend` (Java 11+ HttpClient under the hood);
  *   - parses `--side-effects` from argv. When false (the default),
  *     demos must skip operations that would publish messages, send
  *     events to real recipients, mutate global account settings, or
  *     otherwise leave a footprint we can't reverse — see
  *     [[DemoCtx.sideEffectsEnabled]] for the policy each demo follows;
  *   - tracks created resources in a LIFO stack and deletes them in
  *     reverse order in a finally block, even when `runDemo` throws.
  *
  * Demos that need a unique identifier (for resource names, codes,
  * etc.) should use [[DemoCtx.rand]] / [[DemoCtx.id]] rather than
  * fixed strings — a previous failed run may have left stale resources
  * with the same name on the account.
  */
trait DemoApp {

  /** The category this demo exercises, e.g. `"accounts"`. Used in
    * log lines and in the missing-key message.
    */
  def category: String

  /** Run the demo's API calls. Use [[DemoCtx.register]] to schedule
    * cleanup of every resource you create.
    */
  def runDemo(ctx: DemoCtx): Unit

  /** Convenience entry point for `sbt 'examples/runMain ...AccountsDemo'`. */
  final def main(args: Array[String]): Unit =
    DemoHarness.run(category, args)(runDemo)
}

/** Per-invocation context — client, RNG, side-effects flag, cleanup
  * registry. Passed to every demo's `runDemo`.
  *
  * @param client            the live klaviyo4s client
  * @param sideEffectsEnabled true when `--side-effects` (or `-s`) was
  *                           passed on the command line. Demos must
  *                           gate their irreversible calls on this.
  */
final class DemoCtx private[examples] (
  val client: KlaviyoClient[Identity],
  val sideEffectsEnabled: Boolean
) {
  import scala.collection.mutable

  private val pending: mutable.ListBuffer[Cleanup] = mutable.ListBuffer.empty

  /** A short random suffix unique to this demo run. Use as a name
    * suffix to avoid collisions with stale state on the account.
    */
  val rand: String = UUID.randomUUID().toString.take(8)

  /** A label safe to embed in a Klaviyo resource name. Combines the
    * provided `prefix` with [[rand]] so re-running the same demo on
    * the same account does not collide.
    */
  def id(prefix: String): String = s"klaviyo4s-demo-$prefix-$rand".replace('-', '_')

  /** Register a cleanup action to run in reverse order at the end of
    * the demo. Wrap the deletion call in this; do NOT call delete
    * directly — `runDemo` may throw before the delete site is reached.
    */
  def register(description: String)(undo: => Unit): Unit =
    pending += Cleanup(description, () => undo)

  /** Log a single line tagged with the demo category. */
  def log(message: String): Unit =
    println(s"[demo] $message")

  /** Run a section, printing a header. Convenient for grouping calls
    * in the output.
    */
  def section(title: String)(body: => Unit): Unit = {
    println()
    log(s"--- $title")
    body
  }

  /** Run all pending cleanups in LIFO order. Each failure is logged
    * but does not prevent the others from running.
    */
  private[examples] def runCleanup(): Unit = {
    if (pending.isEmpty) {
      log("cleanup: nothing to undo")
      return
    }
    log(s"cleanup: ${pending.size} resource(s) to remove")
    pending.reverseIterator.foreach { c =>
      Try(c.undo()) match {
        case Success(_) => log(s"  ✓ ${c.description}")
        case Failure(t) => log(s"  ✗ ${c.description}: ${t.getClass.getSimpleName}: ${t.getMessage}")
      }
    }
    pending.clear()
  }
}

private[examples] final case class Cleanup(description: String, undo: () => Unit)

/** Entry point used by every demo's `main`. Centralises the env-var
  * lookup, client wiring, side-effects flag, and the try/finally
  * around `runDemo`.
  *
  * The harness does NOT call `sys.exit` — even when the API key is
  * missing it just prints a notice and returns. That lets
  * [[AllDemos]] iterate through every demo and keep going past one
  * that decided to skip itself.
  */
object DemoHarness {

  /** Run a demo. Called from each [[DemoApp.main]] forwarder. */
  def run(category: String, args: Array[String])(body: DemoCtx => Unit): Unit = {
    val apiKey = sys.env.getOrElse("KLAVIYO_API_KEY", "")
    if (apiKey.isEmpty) {
      println(s"[demo:$category] KLAVIYO_API_KEY env var is not set — skipping demo.")
      println(s"[demo:$category] export KLAVIYO_API_KEY=pk_live_… to enable real API calls.")
      return
    }
    val sideEffectsEnabled = args.exists(a => a == "--side-effects" || a == "-s")
    val backend = DefaultSyncBackend()
    try {
      val client = KlaviyoClient[Identity](
        backend,
        KlaviyoConfig(auth = KlaviyoAuth.PrivateKey(apiKey), revision = GeneratedSpecRevision)
      )
      val ctx = new DemoCtx(client, sideEffectsEnabled)
      ctx.log(s"category=$category sideEffects=$sideEffectsEnabled rand=${ctx.rand}")
      try body(ctx)
      catch {
        case t: Throwable =>
          ctx.log(s"demo body failed: ${t.getClass.getSimpleName}: ${t.getMessage}")
          throw t
      } finally ctx.runCleanup()
    } finally backend.close()
  }
}
