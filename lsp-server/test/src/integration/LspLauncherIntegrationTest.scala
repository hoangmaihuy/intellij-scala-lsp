package org.jetbrains.scalalsP.integration

import org.jetbrains.scalalsP.{LspLauncher, ScalaLspServer}
import org.junit.Assert.*

import java.io.{PipedInputStream, PipedOutputStream}
import java.util.concurrent.TimeUnit

/**
 * Tests that LspLauncher correctly creates JSON-RPC connections without
 * hitting Scala 3 bridge method annotation duplication issues.
 */
class LspLauncherIntegrationTest extends ScalaLspTestBase:

  def testLauncherStartsWithoutDuplicateMethodError(): Unit =
    val serverIn = new PipedInputStream()
    val clientOut = new PipedOutputStream(serverIn)
    val clientIn = new PipedInputStream()
    val serverOut = new PipedOutputStream(clientIn)

    val server = new ScalaLspServer(getProject.getBasePath)

    val serverError = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    val serverStarted = new java.util.concurrent.CountDownLatch(1)
    val serverThread = new Thread(() =>
      try
        serverStarted.countDown()
        LspLauncher.startAndAwait(server, serverIn, serverOut)
      catch
        case e: Exception =>
          serverError.set(e)
          serverStarted.countDown()
    , "lsp-launcher-test")
    serverThread.setDaemon(true)
    serverThread.start()

    assertTrue("Server should start within 5 seconds", serverStarted.await(5, TimeUnit.SECONDS))
    Thread.sleep(500)

    // Close pipes to unblock
    clientOut.close()
    serverThread.join(5000)

    val err = serverError.get()
    if err != null && err.getMessage != null &&
       (err.getMessage.contains("Duplicate") || err.getMessage.contains("Multiple methods")) then
      fail(s"LspLauncher should not throw duplicate/multiple method errors: ${err.getMessage}")
