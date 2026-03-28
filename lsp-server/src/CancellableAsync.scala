package org.jetbrains.scalalsP

import java.util.concurrent.{CompletableFuture, ExecutorService}
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a computation with a cancellation token linked to the returned future.
  * When the future is cancelled (e.g. by lsp4j handling $/cancelRequest),
  * the token is signalled so the computation can check it and bail out early. */
object CancellableAsync:
  def apply[T](executor: ExecutorService)(f: AtomicBoolean => T): CompletableFuture[T] =
    val cancelled = new AtomicBoolean(false)
    val future = CompletableFuture.supplyAsync((() => f(cancelled)): java.util.function.Supplier[T], executor)
    // When lsp4j cancels this future via $/cancelRequest, signal the token
    // so the computation can check it and bail out early
    future.whenComplete: (_, _) =>
      if future.isCancelled then cancelled.set(true)
    future
