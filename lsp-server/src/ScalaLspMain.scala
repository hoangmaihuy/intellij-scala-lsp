package org.jetbrains.scalalsP

import java.io.{InputStream, OutputStream}

/**
 * Entry point for the IntelliJ Scala LSP server.
 *
 * Bootstraps the IntelliJ platform directly (bypassing com.intellij.idea.Main)
 * because IntelliJ 2025.3's WellKnownCommands rejects custom commands in headless mode.
 */
object ScalaLspMain:

  def main(args: Array[String]): Unit =
    args.headOption match
      case None =>
        System.err.println("[ScalaLsp] ERROR: No project path provided. Usage: scala-lsp <projectPath>")
        System.exit(1)
      case Some(projectPath) =>
        run(projectPath)

  private def run(projectPath: String): Unit =

    System.err.println(s"[ScalaLsp] Starting IntelliJ Scala LSP server for project: $projectPath")

    try
      IntellijBootstrap.initialize()
      System.err.println("[ScalaLsp] IntelliJ platform initialized")
      startLspServer(projectPath, System.in, System.out)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

  private def startLspServer(projectPath: String, in: InputStream, out: OutputStream): Unit =
    val server = new ScalaLspServer(projectPath)

    // Create Launcher in Java to avoid Scala 3 bridge method annotation duplication
    // that causes "Duplicate RPC method" errors in lsp4j ServiceEndpoints scanning
    LspLauncher.startAndAwait(server, in, out)

    System.err.println("[ScalaLsp] LSP connection closed")
    System.exit(0)
