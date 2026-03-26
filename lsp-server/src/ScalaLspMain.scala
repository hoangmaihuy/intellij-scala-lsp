package org.jetbrains.scalalsP

/**
 * Lightweight entry point for non-IntelliJ operations.
 * Used for --stop and --list-projects which only talk to a running daemon.
 *
 * The main LSP server (daemon and stdio modes) is started via
 * com.intellij.idea.Main -> ScalaLspApplicationStarter.
 */
object ScalaLspMain:

  private val CACHE_DIR = s"${System.getProperty("user.home")}/.cache/intellij-scala-lsp"

  def main(args: Array[String]): Unit =
    args.toList match
      case "--stop" :: _           => stopMode()
      case "--list-projects" :: _  => listProjectsMode()
      case _ =>
        System.err.println("Usage: ScalaLspMain --stop | --list-projects")
        System.err.println("For LSP server, use: com.intellij.idea.Main scala-lsp [--daemon] [args]")
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
