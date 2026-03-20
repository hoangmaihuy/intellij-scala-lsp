package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.jetbrains.scalalsP.{DaemonServer, JavaTestLanguageClient, ProjectRegistry}
import org.junit.Assert.*

import java.net.Socket
import java.util.concurrent.{CompletableFuture, TimeUnit}

class DaemonServerIntegrationTest extends ScalaLspTestBase:

  private var daemon: DaemonServer = scala.compiletime.uninitialized
  private var daemonThread: Thread = scala.compiletime.uninitialized
  private var registry: ProjectRegistry = scala.compiletime.uninitialized
  private val TEST_PORT = 15007

  override def setUp(): Unit =
    super.setUp()
    // super.setUp() already counts down BootstrapState.bootstrapComplete
    registry = ProjectRegistry()
    registry.registerForTesting(getProject.getBasePath, getProject)
    daemon = DaemonServer(registry)
    daemon.bind(TEST_PORT)
    daemonThread = new Thread(() =>
      try daemon.acceptLoop()
      catch case _: Exception => ()
    , "daemon-test")
    daemonThread.setDaemon(true)
    daemonThread.start()
    Thread.sleep(300) // let accept loop start

  override def tearDown(): Unit =
    try
      // Unregister the test project before stopping, so closeAll() won't dispose
      // the project managed by BasePlatformTestCase
      registry.unregisterForTesting(getProject.getBasePath)
      daemon.stop()
      daemonThread.join(5000)
    catch case _: Exception => ()
    super.tearDown()

  private def requestOffEdt[T](timeout: Int = 15)(body: => T): T =
    val future = CompletableFuture.supplyAsync[T](() => body)
    val deadline = System.currentTimeMillis() + timeout * 1000L
    while !future.isDone && System.currentTimeMillis() < deadline do
      com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    future.get(1, TimeUnit.SECONDS)

  def testDaemonAcceptsConnectionAndInitializes(): Unit =
    val socket = new Socket("localhost", TEST_PORT)
    val launcher = new Launcher.Builder[LanguageServer]()
      .setLocalService(new JavaTestLanguageClient())
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .create()
    val proxy = launcher.getRemoteProxy
    launcher.startListening()

    val result = requestOffEdt() {
      val params = new InitializeParams()
      params.setProcessId(1)
      params.setRootUri(s"file://${getProject.getBasePath}")
      params.setCapabilities(new ClientCapabilities())
      proxy.initialize(params).get(30, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    assertNotNull(result.getCapabilities)
    assertEquals("intellij-scala-lsp", result.getServerInfo.getName)
    socket.close()

  def testShutdownProtocolStopsDaemon(): Unit =
    // Unregister the test project before sending shutdown, so daemon.stop()/closeAll()
    // won't dispose the project managed by BasePlatformTestCase
    registry.unregisterForTesting(getProject.getBasePath)
    // Send a raw shutdown JSON-RPC message to the daemon
    val socket = new Socket("localhost", TEST_PORT)
    val out = socket.getOutputStream
    val shutdownMsg = """{"jsonrpc":"2.0","id":1,"method":"shutdown","params":null}"""
    val header = s"Content-Length: ${shutdownMsg.length}\r\n\r\n"
    out.write(header.getBytes)
    out.write(shutdownMsg.getBytes)
    out.flush()
    socket.close()

    // Daemon should stop within 5 seconds
    daemonThread.join(5000)
    assertFalse("Daemon thread should have stopped", daemonThread.isAlive)
