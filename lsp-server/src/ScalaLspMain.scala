package org.jetbrains.scalalsP

import com.intellij.testFramework.LoggedErrorProcessor

import java.io.{OutputStream, PrintStream}
import java.util.EnumSet

/**
 * Entry point for the IntelliJ Scala LSP server.
 *
 * Starts the LSP JSON-RPC listener immediately so the client can connect,
 * then bootstraps IntelliJ in a background thread. The server's initialize()
 * handler waits for bootstrap to complete before opening the project.
 */
object ScalaLspMain:

  /** Latch that the initialize handler waits on before opening the project */
  val bootstrapComplete = BootstrapState.bootstrapComplete

  def main(args: Array[String]): Unit =
    val projectPath = args.headOption.getOrElse("")
    System.err.println("[ScalaLsp] Starting IntelliJ Scala LSP server...")

    // CRITICAL: Save raw stdout for JSON-RPC, then redirect System.out to stderr.
    // IntelliJ's bootstrap (TestApplicationManager, plugins, etc.) may print to
    // System.out, which would corrupt the JSON-RPC protocol stream.
    val jsonRpcOut = System.out
    System.setOut(new PrintStream(System.err, true))

    // Start bootstrap in background so the LSP listener can accept connections immediately
    val bootstrapThread = new Thread(() =>
      try
        // Install lenient error processor BEFORE bootstrap.
        // TestApplicationManager installs TestLoggerFactory which converts LOG.error() into
        // thrown exceptions. Non-critical errors (stale indexes, missing SDKs) should be
        // logged, not crash the LSP server.
        LoggedErrorProcessor.executeWith(new LoggedErrorProcessor:
          override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
            EnumSet.of(LoggedErrorProcessor.Action.STDERR)
        )
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
      // Start LSP listener on the saved raw stdout (not the redirected System.out)
      val server = new ScalaLspServer(projectPath)
      LspLauncher.startAndAwait(server, System.in, jsonRpcOut)
      System.err.println("[ScalaLsp] LSP connection closed")
      System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)
