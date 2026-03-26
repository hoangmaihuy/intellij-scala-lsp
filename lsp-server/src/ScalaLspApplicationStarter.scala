package org.jetbrains.scalalsP

import com.intellij.openapi.application.ApplicationStarter
import java.io.PrintStream

// IntelliJ ApplicationStarter extension for the LSP server.
// Invoked by com.intellij.idea.Main after full platform initialization.
// Registered in plugin.xml with id="scala-lsp".
//
// Supports modes:
//   scala-lsp --daemon [project...]   Start TCP daemon, pre-warm projects
//   scala-lsp [projectPath]           Stdio LSP mode (default)
class ScalaLspApplicationStarter extends ApplicationStarter:

  override def main(args: java.util.List[String]): Unit =
    // args = ["scala-lsp", ...rest]
    val restArgs = if args.size() > 1 then
      (1 until args.size()).map(args.get).toList
    else List.empty

    // Platform is fully initialized — signal bootstrap complete for ScalaLspServer
    BootstrapState.bootstrapComplete.countDown()

    restArgs match
      case "--daemon" :: projects => daemonMode(projects)
      case other                 => stdioMode(other.headOption.getOrElse(""))

  private def daemonMode(projectPaths: List[String]): Unit =
    System.err.println("[ScalaLsp] Starting daemon mode...")
    val port = Option(System.getenv("LSP_PORT")).map(_.toInt).getOrElse(5007)
    val cacheDir = s"${System.getProperty("user.home")}/.cache/intellij-scala-lsp"

    // Redirect stdout to stderr — IntelliJ may write to stdout
    System.setOut(new PrintStream(System.err, true))

    val registry = ProjectRegistry()
    val daemon = DaemonServer(registry)

    try
      val boundPort = daemon.bind(port)
      java.nio.file.Files.createDirectories(java.nio.file.Path.of(cacheDir))
      java.nio.file.Files.writeString(java.nio.file.Path.of(s"$cacheDir/daemon.pid"), ProcessHandle.current().pid().toString)
      java.nio.file.Files.writeString(java.nio.file.Path.of(s"$cacheDir/daemon.port"), boundPort.toString)

      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        java.io.File(s"$cacheDir/daemon.pid").delete()
        java.io.File(s"$cacheDir/daemon.port").delete()
        ()
      }))

      System.err.println(s"[ScalaLsp] Daemon ready on port $boundPort")

      // Pre-warm projects in background
      val warmupThread = new Thread(() =>
        for path <- projectPaths do
          try
            System.err.println(s"[ScalaLsp] Pre-warming project: $path")
            registry.openProject(path)
          catch case e: Exception =>
            System.err.println(s"[ScalaLsp] Failed to pre-warm $path: ${e.getMessage}")
      , "project-warmup")
      warmupThread.setDaemon(true)
      warmupThread.start()

      daemon.acceptLoop() // blocks
    catch
      case e: java.net.BindException =>
        System.err.println(s"[ScalaLsp] Port $port already in use. Set LSP_PORT env var to use a different port.")
        System.exit(1)

  private def stdioMode(projectPath: String): Unit =
    System.err.println("[ScalaLsp] Starting stdio LSP mode...")

    // Save raw stdout for JSON-RPC, redirect System.out to stderr
    val jsonRpcOut = System.out
    System.setOut(new PrintStream(System.err, true))

    try
      val server = new ScalaLspServer(projectPath)
      LspLauncher.startAndAwait(server, System.in, jsonRpcOut)
      System.err.println("[ScalaLsp] LSP connection closed")
      System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)
