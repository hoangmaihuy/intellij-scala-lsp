package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.json.{JsonRpcMethod, MessageJsonHandler, StreamMessageConsumer}
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.jsonrpc.{Endpoint, RemoteEndpoint}
import org.eclipse.lsp4j.services.*
import org.jetbrains.scalalsP.{JavaTestLanguageClient, LspLauncher, ScalaLspServer}
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

    // Start the server — if bridge method scanning causes duplicates,
    // LspLauncher.startAndAwait throws immediately during Launcher creation
    val serverError = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    val serverStarted = new java.util.concurrent.CountDownLatch(1)
    val serverThread = new Thread(() =>
      try
        // The Launcher.Builder.create() happens synchronously inside startAndAwait.
        // If it throws "Duplicate RPC method", it happens before blocking on I/O.
        serverStarted.countDown()
        LspLauncher.startAndAwait(server, serverIn, serverOut)
      catch
        case e: Exception =>
          serverError.set(e)
          serverStarted.countDown()
    , "lsp-launcher-test")
    serverThread.setDaemon(true)
    serverThread.start()

    // Wait for server to start or fail
    assertTrue("Server should start within 5 seconds", serverStarted.await(5, TimeUnit.SECONDS))

    // Give a moment for any delayed initialization
    Thread.sleep(500)

    // Close the pipes to unblock startAndAwait
    clientOut.close()

    // Wait for thread to finish
    serverThread.join(5000)

    // Check no duplicate method errors
    val err = serverError.get()
    if err != null && (err.getMessage.contains("Duplicate") || err.getMessage.contains("Multiple methods")) then
      fail(s"LspLauncher should not throw duplicate/multiple method errors: ${err.getMessage}")
