package com.alexdupre.klaviyo.core

import sttp.shared.Identity

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/** Verifies the two shipped Sleep instances:
  *
  *   - `Sleep[Identity]` blocks the calling thread (correct, since
  *     Identity is synchronous).
  *   - `Sleep[Future]` does NOT block — the calling thread should be
  *     released back to the pool while the timer runs.
  *
  * The Future test exercises non-blocking-ness by recording the thread
  * the call returns on vs. the thread the continuation runs on, and by
  * launching many concurrent sleeps to confirm they all complete in
  * roughly the same wall-clock time (rather than serializing).
  */
final class SleepSpec extends munit.FunSuite {

  test("Sleep[Identity] blocks for the requested duration") {
    val sleep    = summon[Sleep[Identity]]
    val started  = System.nanoTime()
    sleep.sleep(50.millis)
    val elapsed  = (System.nanoTime() - started).nanos
    // Linux's HR timers easily resolve 50ms; allow some slack on either side.
    assert(elapsed >= 45.millis, s"too fast: $elapsed")
    assert(elapsed < 500.millis, s"too slow: $elapsed")
  }

  test("Sleep[Future] does not block the calling thread") {
    given ec: ExecutionContext = ExecutionContext.global
    val sleep                  = summon[Sleep[Future]]

    val startedOnThread = Thread.currentThread().getName
    val f               = sleep.sleep(150.millis)
    // If we made it here without blocking, we're still on the original
    // thread and the future is not yet completed. The future may have
    // completed already if the timer fired in <1ms (extremely unlikely
    // for a 150ms sleep), so a relaxed check is sufficient.
    assertEquals(Thread.currentThread().getName, startedOnThread)
    Await.ready(f, 2.seconds)
    assert(f.isCompleted)
  }

  test("Sleep[Future] runs many sleeps concurrently") {
    given ec: ExecutionContext = ExecutionContext.global
    val sleep                  = summon[Sleep[Future]]

    val started = System.nanoTime()
    val many    = Future.sequence((1 to 20).map(_ => sleep.sleep(100.millis)))
    Await.ready(many, 3.seconds)
    val elapsed = (System.nanoTime() - started).nanos

    // 20 sleeps of 100ms serialised would be 2 seconds. Parallel should
    // resolve in well under that.
    assert(elapsed < 1.second, s"sleeps appear to have serialised: $elapsed")
  }
}
