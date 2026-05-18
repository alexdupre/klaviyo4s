package com.alexdupre.klaviyo.core

import sttp.shared.Identity

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}

/** A minimal "delay" capability for an effect type `F[_]`.
  *
  * sttp's `MonadError[F]` provides composition (`map`/`flatMap`/`error`)
  * but no notion of *scheduling a continuation in the future*. The retry
  * policy in [[internal.Executor]] needs exactly that, so we carry it
  * separately as a tiny single-method type class.
  *
  * Two given instances ship with the library:
  *
  *   - [[Sleep.identitySleep]] uses `Thread.sleep` — appropriate for the
  *     synchronous `Identity` backend where there is no other thread to
  *     yield to.
  *   - [[Sleep.futureSleep]] is non-blocking: it schedules the
  *     completion of a `Promise[Unit]` via a shared daemon
  *     `ScheduledExecutorService`. No thread is parked during the delay.
  *
  * Users of other effect types (cats-effect `IO`, ZIO, ...) provide their
  * own given instance — typically by calling the effect type's native
  * `IO.sleep` / `ZIO.sleep`.
  */
trait Sleep[F[_]] {

  /** Suspend execution for the given duration, then resume with `()`.
    *
    * Implementations must respect the duration as a non-blocking wait
    * whenever the underlying effect can be implemented that way (Future,
    * IO, ZIO, ...). The `Identity` instance is the only exception.
    */
  def sleep(duration: FiniteDuration): F[Unit]
}

object Sleep {

  /** Blocking sleep for the synchronous `Identity` backend.
    *
    * `Identity[X] = X`, so the entire computation runs on the calling
    * thread. Parking that thread with `Thread.sleep` is the only sensible
    * implementation — there is no continuation to schedule elsewhere.
    */
  given identitySleep: Sleep[Identity] = new Sleep[Identity] {
    def sleep(duration: FiniteDuration): Unit = Thread.sleep(duration.toMillis.max(0))
  }

  /** Non-blocking sleep for `Future`-based backends.
    *
    * Schedules a `Promise[Unit]` completion via a shared daemon
    * `ScheduledExecutorService` and returns the promise's `Future`. No
    * thread parks during the delay; the user's `ExecutionContext`
    * resumes the continuation when the timer fires.
    *
    * The scheduler is a single-threaded daemon — sleeps are cheap enough
    * that one timer thread is sufficient, and the daemon flag lets the
    * JVM shut down without an explicit close call.
    */
  // `ec` is required at summon-site rather than used in the body — completing
  // a Promise is O(1) so we do it on the scheduler thread; the user's
  // continuations consume the EC at their own composition points. Forcing it
  // here turns a missing EC into a clear error at client construction rather
  // than later in user code.
  given futureSleep(using @unused ec: ExecutionContext): Sleep[Future] = new Sleep[Future] {
    def sleep(duration: FiniteDuration): Future[Unit] = {
      val promise = Promise[Unit]()
      // Negative or zero durations resolve immediately, matching the
      // semantics of a `Future.successful(())`. Avoids a useless trip
      // through the scheduler for the common "no delay" path.
      val millis = duration.toMillis
      if (millis <= 0) {
        promise.success(())
      } else {
        SharedScheduler.schedule(
          () => promise.success(()),
          millis,
          TimeUnit.MILLISECONDS
        )
      }
      promise.future
    }
  }

  /** Shared scheduler backing every `Sleep[Future]` instance.
    *
    * One daemon thread is enough — `Runnable`s submitted here only flip a
    * `Promise`, which is cheap; the user's `ExecutionContext` actually
    * runs the continuation work.
    */
  private object SharedScheduler {

    private val factory: ThreadFactory = (r: Runnable) => {
      val t = new Thread(r, "klaviyo4s-scheduler")
      t.setDaemon(true)
      t
    }

    private val underlying: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor(factory)

    def schedule(task: Runnable, delay: Long, unit: TimeUnit): Unit = {
      // Discarding the returned ScheduledFuture is intentional — we
      // never need to cancel because the task only completes a Promise.
      val _ = underlying.schedule(task, delay, unit)
    }
  }
}
