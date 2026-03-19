package org.jetbrains.scalalsP

import java.util.concurrent.CountDownLatch

/**
 * Entry point for the IntelliJ Scala LSP server.
 *
 * Starts the LSP JSON-RPC listener immediately so the client can connect,
 * then bootstraps IntelliJ in a background thread. The server's initialize()
 * handler waits for bootstrap to complete before opening the project.
 */
object ScalaLspMain:

  /** Latch that the initialize handler waits on before opening the project */
  val bootstrapComplete = new CountDownLatch(1)

  def main(args: Array[String]): Unit =
    val projectPath = args.headOption.getOrElse("")
    System.err.println("[ScalaLsp] Starting IntelliJ Scala LSP server...")

    // Start bootstrap in background so the LSP listener can accept connections immediately
    val bootstrapThread = new Thread(() =>
      try
        IntellijBootstrap.initialize()
        System.err.println("[ScalaLsp] IntelliJ platform initialized")
      catch
        case e: Exception =>
          System.err.println(s"[ScalaLsp] Bootstrap failed: ${e.getMessage}")
          e.printStackTrace(System.err)
      finally
        bootstrapComplete.countDown()
    , "intellij-bootstrap")
    bootstrapThread.setDaemon(true)
    bootstrapThread.start()

    try
      // Start LSP listener immediately — it reads JSON-RPC from stdin
      // The server's initialize() waits for bootstrapComplete before proceeding
      val server = new ScalaLspServer(projectPath)
      LspLauncher.startAndAwait(server, System.in, System.out)
      System.err.println("[ScalaLsp] LSP connection closed")
      System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)
