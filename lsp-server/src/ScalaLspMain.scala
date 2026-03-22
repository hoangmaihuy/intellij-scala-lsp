package org.jetbrains.scalalsP

import com.intellij.testFramework.LoggedErrorProcessor
import java.io.PrintStream
import java.util.EnumSet

/**
 * Entry point for the IntelliJ Scala LSP server.
 *
 * Supports these modes:
 *   --daemon [project...]    Bootstrap IntelliJ, pre-warm projects, start TCP server
 *   --stop                   Send shutdown signal to running daemon, exit
 *   --list-projects          List projects open in the running daemon
 *   (default)                Stdio LSP mode (original behavior)
 */
object ScalaLspMain:

  /** Latch that the initialize handler waits on before opening the project */
  val bootstrapComplete = BootstrapState.bootstrapComplete

  private val CACHE_DIR = s"${System.getProperty("user.home")}/.cache/intellij-scala-lsp"

  def main(args: Array[String]): Unit =
    args.toList match
      case "--daemon" :: projects  => daemonMode(projects)
      case "--stop" :: _           => stopMode()
      case "--list-projects" :: _  => listProjectsMode()
      case other                   => stdioMode(other.headOption.getOrElse(""))

  private def daemonMode(projectPaths: List[String]): Unit =
    System.err.println("[ScalaLsp] Starting daemon mode...")
    val port = Option(System.getenv("LSP_PORT")).map(_.toInt).getOrElse(5007)

    System.setOut(new PrintStream(System.err, true))

    // Install global lenient error processor via reflection
    // (executeWith is thread-local; daemon needs it on all threads)
    try
      val field = classOf[LoggedErrorProcessor].getDeclaredField("ourInstance")
      field.setAccessible(true)
      field.set(null, new LoggedErrorProcessor:
        override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
          EnumSet.of(LoggedErrorProcessor.Action.STDERR)
      )
    catch case e: Exception =>
      System.err.println(s"[ScalaLsp] Warning: could not install global error processor: ${e.getMessage}")
      // Fall back to thread-local
      LoggedErrorProcessor.executeWith(new LoggedErrorProcessor:
        override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
          EnumSet.of(LoggedErrorProcessor.Action.STDERR)
      )

    // Bootstrap IntelliJ
    try
      IntellijBootstrap.initialize()
      System.err.println("[ScalaLsp] IntelliJ platform initialized")
      BootstrapState.bootstrapComplete.countDown()
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Bootstrap failed: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

    // Pre-warm projects
    val registry = ProjectRegistry()
    for path <- projectPaths do
      try
        System.err.println(s"[ScalaLsp] Pre-warming project: $path")
        registry.openProject(path)
      catch case e: Exception =>
        System.err.println(s"[ScalaLsp] Failed to pre-warm $path: ${e.getMessage}")

    // Start TCP server
    val daemon = DaemonServer(registry)

    // Clean up state files on exit
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      java.io.File(s"$CACHE_DIR/daemon.pid").delete()
      java.io.File(s"$CACHE_DIR/daemon.port").delete()
      ()
    }))

    try
      // Bind FIRST, then write state files, then accept
      val boundPort = daemon.bind(port)
      java.nio.file.Files.createDirectories(java.nio.file.Path.of(CACHE_DIR))
      java.nio.file.Files.writeString(java.nio.file.Path.of(s"$CACHE_DIR/daemon.pid"), ProcessHandle.current().pid().toString)
      java.nio.file.Files.writeString(java.nio.file.Path.of(s"$CACHE_DIR/daemon.port"), boundPort.toString)
      System.err.println(s"[ScalaLsp] Daemon ready on port $boundPort")
      daemon.acceptLoop() // blocks
    catch
      case e: java.net.BindException =>
        System.err.println(s"[ScalaLsp] Port $port already in use. Set LSP_PORT env var to use a different port.")
        System.exit(1)

  private def stopMode(): Unit =
    val portFile = java.nio.file.Path.of(s"$CACHE_DIR/daemon.port")
    val pidFile = java.nio.file.Path.of(s"$CACHE_DIR/daemon.pid")
    if !java.nio.file.Files.exists(portFile) then
      System.err.println("[ScalaLsp] No daemon running (no port file)")
      System.exit(1)

    val port = java.nio.file.Files.readString(portFile).trim.toInt
    val pid = if java.nio.file.Files.exists(pidFile) then
      java.nio.file.Files.readString(pidFile).trim.toLong
    else -1L

    try
      val socket = new java.net.Socket("localhost", port)
      val out = socket.getOutputStream
      val shutdownMsg = """{"jsonrpc":"2.0","id":1,"method":"shutdown","params":null}"""
      val header = s"Content-Length: ${shutdownMsg.length}\r\n\r\n"
      out.write(header.getBytes)
      out.write(shutdownMsg.getBytes)
      out.flush()
      socket.close()
      System.err.println(s"[ScalaLsp] Shutdown signal sent to daemon on port $port")
    catch
      case _: java.net.ConnectException =>
        System.err.println("[ScalaLsp] Daemon not responding, cleaning up stale files")

    java.nio.file.Files.deleteIfExists(portFile)
    java.nio.file.Files.deleteIfExists(pidFile)
    if pid > 0 then ProcessHandle.of(pid).ifPresent(_.destroy())

  private def listProjectsMode(): Unit =
    val projectsFile = java.nio.file.Path.of(s"$CACHE_DIR/daemon.projects")
    val portFile = java.nio.file.Path.of(s"$CACHE_DIR/daemon.port")
    if !java.nio.file.Files.exists(portFile) then
      System.err.println("No daemon running.")
      System.exit(1)

    val port = java.nio.file.Files.readString(portFile).trim
    if !java.nio.file.Files.exists(projectsFile) then
      System.err.println(s"Daemon running on port $port (no projects open)")
      System.exit(0)

    val projects = java.nio.file.Files.readString(projectsFile).trim
    if projects.isEmpty then
      System.err.println(s"Daemon running on port $port (no projects open)")
    else
      System.err.println(s"Daemon running on port $port")
      System.err.println(s"Open projects:")
      for line <- projects.split("\n") if line.nonEmpty do
        System.err.println(s"  $line")

  private def stdioMode(projectPath: String): Unit =
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
        BootstrapState.bootstrapComplete.countDown()
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
