package org.jetbrains.scalalsP

import com.intellij.openapi.application.ApplicationStarter
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient

import java.io.{InputStream, OutputStream}

// IntelliJ ApplicationStarter extension that bootstraps the LSP server.
// Command name "scala-lsp" is registered in plugin.xml.
class ScalaLspApplicationStarter extends ApplicationStarter:

  override def main(args: java.util.List[String]): Unit =
    val projectPath = if args.size() > 1 then args.get(1) else
      System.err.println("[ScalaLsp] ERROR: No project path provided. Usage: idea scala-lsp <projectPath>")
      System.exit(1)
      return

    System.err.println(s"[ScalaLsp] Starting IntelliJ Scala LSP server for project: $projectPath")

    try
      startLspServer(projectPath, System.in, System.out)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

  private def startLspServer(projectPath: String, in: InputStream, out: OutputStream): Unit =
    val server = new ScalaLspServer(projectPath)

    val launcher = new Launcher.Builder[LanguageClient]()
      .setLocalService(server)
      .setRemoteInterface(classOf[LanguageClient])
      .setInput(in)
      .setOutput(out)
      .create()

    val client = launcher.getRemoteProxy
    server.connect(client)

    System.err.println("[ScalaLsp] LSP server started, listening on stdin/stdout")

    // This blocks until the connection is closed
    launcher.startListening().get()

    System.err.println("[ScalaLsp] LSP connection closed")
    System.exit(0)
