package org.jetbrains.scalalsP

import org.junit.Assert.*
import org.junit.Test

import java.util.concurrent.{CancellationException, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

class CancellableAsyncTest:
  BootstrapState.bootstrapComplete.countDown()

  private val executor = Executors.newCachedThreadPool: r =>
    val t = Thread(r, "test-cancellable")
    t.setDaemon(true)
    t

  @Test def testNormalCompletion(): Unit =
    val future = CancellableAsync(executor): cancelled =>
      42
    assertEquals(42, future.get(5, TimeUnit.SECONDS))

  @Test def testCancelledTokenIsInitiallyFalse(): Unit =
    val tokenSeen = new AtomicBoolean(true)
    val latch = new CountDownLatch(1)
    val future = CancellableAsync(executor): cancelled =>
      tokenSeen.set(cancelled.get())
      latch.countDown()
      "done"
    latch.await(5, TimeUnit.SECONDS)
    assertFalse("Cancellation token should start as false", tokenSeen.get())

  @Test def testCancelSignalsToken(): Unit =
    val started = new CountDownLatch(1)
    val tokenSignalled = new CountDownLatch(1)
    val future = CancellableAsync(executor): cancelled =>
      started.countDown()
      // Spin until cancellation is signalled
      while !cancelled.get() do Thread.sleep(10)
      tokenSignalled.countDown()
      "cancelled"
    // Wait for the computation to start
    assertTrue("Task should start", started.await(5, TimeUnit.SECONDS))
    // Cancel the future (simulating $/cancelRequest from lsp4j)
    future.cancel(true)
    // The cancellation token should be signalled, allowing the computation to exit
    assertTrue("Cancellation token should be signalled", tokenSignalled.await(5, TimeUnit.SECONDS))

  @Test def testCancelledFutureIsCancelled(): Unit =
    val started = new CountDownLatch(1)
    val future = CancellableAsync(executor): cancelled =>
      started.countDown()
      while !cancelled.get() do Thread.sleep(10)
      "result"
    started.await(5, TimeUnit.SECONDS)
    future.cancel(true)
    assertTrue("Future should be cancelled", future.isCancelled)

  @Test def testExceptionPropagates(): Unit =
    val future = CancellableAsync(executor): cancelled =>
      throw RuntimeException("boom")
    try
      future.get(5, TimeUnit.SECONDS)
      fail("Should have thrown")
    catch
      case e: java.util.concurrent.ExecutionException =>
        assertTrue(e.getCause.isInstanceOf[RuntimeException])
        assertEquals("boom", e.getCause.getMessage)

  @Test def testCancelBeforeStartSignalsToken(): Unit =
    // Use a single-thread executor with a blocking task to ensure queueing
    val blockingExecutor = Executors.newSingleThreadExecutor: r =>
      val t = Thread(r, "test-blocking")
      t.setDaemon(true)
      t
    val blocker = new CountDownLatch(1)
    // Block the executor's only thread
    blockingExecutor.submit((() => blocker.await(10, TimeUnit.SECONDS)): Runnable)

    val tokenValue = new AtomicBoolean(false)
    val taskRan = new AtomicBoolean(false)
    val future = CancellableAsync(blockingExecutor): cancelled =>
      tokenValue.set(cancelled.get())
      taskRan.set(true)
      "result"

    // Cancel before the task can run
    future.cancel(true)
    // Unblock the executor
    blocker.countDown()
    // Give the task a moment to potentially run
    Thread.sleep(200)

    // The future should be cancelled regardless of whether the task ran
    assertTrue("Future should be cancelled", future.isCancelled)
    // If the task did run, the token should have been true
    if taskRan.get() then
      assertTrue("Token should be signalled if task ran after cancel", tokenValue.get())

    blockingExecutor.shutdown()

  @Test def testMultipleConcurrentRequests(): Unit =
    // Simulate multiple independent workspace/symbol requests running concurrently
    val results = (1 to 5).map: i =>
      CancellableAsync(executor): cancelled =>
        Thread.sleep(50) // simulate some work
        if cancelled.get() then -1 else i
    val values = results.map(_.get(5, TimeUnit.SECONDS))
    assertEquals(List(1, 2, 3, 4, 5), values.toList)

  @Test def testCancelPropagatesThroughWhenComplete(): Unit =
    // Simulates the pattern: logged() wraps the future with whenComplete,
    // and lsp4j cancels the ORIGINAL future (not the whenComplete derivative).
    // This verifies that returning the original future preserves cancellation.
    val started = new CountDownLatch(1)
    val tokenSignalled = new CountDownLatch(1)
    val original = CancellableAsync(executor): cancelled =>
      started.countDown()
      while !cancelled.get() do Thread.sleep(10)
      tokenSignalled.countDown()
      "done"
    // Simulate what logged() does: add a whenComplete callback
    val logged = original.whenComplete: (_, _) =>
      () // logging side-effect
    // If we cancel the DERIVED future (logged), it does NOT cancel original
    started.await(5, TimeUnit.SECONDS)
    logged.cancel(true)
    // The token should NOT be signalled because we cancelled the wrong future
    assertFalse("Cancelling derived future should not signal token",
      tokenSignalled.await(500, TimeUnit.MILLISECONDS))
    // Now cancel the ORIGINAL future — this is what the fix ensures
    original.cancel(true)
    assertTrue("Cancelling original future should signal token",
      tokenSignalled.await(5, TimeUnit.SECONDS))

  @Test def testCancelOneDoesNotAffectOthers(): Unit =
    val started = new CountDownLatch(3)
    val futures = (1 to 3).map: i =>
      CancellableAsync(executor): cancelled =>
        started.countDown()
        // Task 2 waits for cancellation, others complete immediately
        if i == 2 then
          while !cancelled.get() do Thread.sleep(10)
          -1
        else
          i
    started.await(5, TimeUnit.SECONDS)
    // Cancel only the second future
    futures(1).cancel(true)
    assertEquals(1, futures(0).get(5, TimeUnit.SECONDS))
    assertTrue("Second future should be cancelled", futures(1).isCancelled)
    assertEquals(3, futures(2).get(5, TimeUnit.SECONDS))
