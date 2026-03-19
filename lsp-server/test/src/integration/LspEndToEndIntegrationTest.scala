package org.jetbrains.scalalsP.integration

import com.intellij.openapi.application.ApplicationManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.jetbrains.scalalsP.{JavaTestLanguageClient, LspLauncher, ScalaLspServer}
import org.junit.Assert.*

import java.io.{PipedInputStream, PipedOutputStream}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
 * End-to-end integration test that exercises the full LSP JSON-RPC protocol
 * through LspLauncher with piped streams.
 *
 * Note: BasePlatformTestCase runs tests on EDT. To avoid deadlocks when
 * server-side handlers need read/write actions, we send requests from a
 * pooled thread and pump EDT events while waiting.
 */
class LspEndToEndIntegrationTest extends ScalaLspTestBase:

  private var clientProxy: LanguageServer = scala.compiletime.uninitialized
  private var clientLauncher: Launcher[LanguageServer] = scala.compiletime.uninitialized
  private var serverThread: Thread = scala.compiletime.uninitialized
  private var serverOut: PipedOutputStream = scala.compiletime.uninitialized
  private var clientOut: PipedOutputStream = scala.compiletime.uninitialized
  private val testClient = new JavaTestLanguageClient()

  override def setUp(): Unit =
    super.setUp()

    val serverIn = new PipedInputStream(65536)
    clientOut = new PipedOutputStream(serverIn)
    val clientIn = new PipedInputStream(65536)
    serverOut = new PipedOutputStream(clientIn)

    val server = new ScalaLspServer(getProject.getBasePath, projectManager)

    serverThread = new Thread(() =>
      try LspLauncher.startAndAwait(server, serverIn, serverOut)
      catch case _: Exception => ()
    , "lsp-e2e-server")
    serverThread.setDaemon(true)
    serverThread.start()

    clientLauncher = new Launcher.Builder[LanguageServer]()
      .setLocalService(testClient)
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(clientIn)
      .setOutput(clientOut)
      .create()
    clientProxy = clientLauncher.getRemoteProxy
    clientLauncher.startListening()

  override def tearDown(): Unit =
    try
      clientOut.close()
      serverOut.close()
      serverThread.join(5000)
    catch case _: Exception => ()
    super.tearDown()

  /** Run a request off-EDT and pump EDT events while waiting for the result. */
  private def requestOffEdt[T](timeout: Int = 15)(body: => T): T =
    val future = CompletableFuture.supplyAsync[T](() => body)
    val deadline = System.currentTimeMillis() + timeout * 1000L
    while !future.isDone && System.currentTimeMillis() < deadline do
      // Pump EDT events so read/write actions can proceed
      com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    future.get(1, TimeUnit.SECONDS)

  private def doInitialize(): InitializeResult =
    requestOffEdt() {
      val params = new InitializeParams()
      params.setProcessId(ProcessHandle.current().pid().toInt)
      params.setRootUri(s"file://${getProject.getBasePath}")
      params.setCapabilities(new ClientCapabilities())
      val result = clientProxy.initialize(params).get(10, TimeUnit.SECONDS)
      clientProxy.initialized(new InitializedParams())
      result
    }

  private def openFile(uri: String, content: String): Unit =
    val didOpenParams = new DidOpenTextDocumentParams()
    didOpenParams.setTextDocument(new TextDocumentItem(uri, "scala", 1, content))
    clientProxy.getTextDocumentService.didOpen(didOpenParams)
    Thread.sleep(300)

  def testInitializeReturnsCapabilities(): Unit =
    val result = doInitialize()
    assertNotNull("Initialize should return a result", result)
    assertNotNull("Should have capabilities", result.getCapabilities)
    assertTrue("Should support hover", result.getCapabilities.getHoverProvider.getLeft)
    assertTrue("Should support definition", result.getCapabilities.getDefinitionProvider.getLeft)
    assertTrue("Should support references", result.getCapabilities.getReferencesProvider.getLeft)
    assertNotNull("Should have server info", result.getServerInfo)
    assertEquals("intellij-scala-lsp", result.getServerInfo.getName)

  def testHoverViaWire(): Unit =
    doInitialize()

    val uri = configureScalaFile(
      """object Main:
        |  val greeting: String = "hello"
        |""".stripMargin
    )
    openFile(uri, myFixture.getFile.getText)

    val result = requestOffEdt() {
      val hoverParams = new HoverParams()
      hoverParams.setTextDocument(new TextDocumentIdentifier(uri))
      hoverParams.setPosition(new Position(1, 6))
      clientProxy.getTextDocumentService.hover(hoverParams).get(10, TimeUnit.SECONDS)
    }
    if result != null then
      assertNotNull("Hover should have contents", result.getContents)
      val value = result.getContents.getRight.getValue
      assertTrue(s"Hover should return non-empty content, got: $value", value.nonEmpty)

  def testDefinitionViaWire(): Unit =
    doInitialize()

    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    openFile(uri, myFixture.getFile.getText)

    val result = requestOffEdt() {
      val defParams = new DefinitionParams()
      defParams.setTextDocument(new TextDocumentIdentifier(uri))
      defParams.setPosition(new Position(2, 12))
      clientProxy.getTextDocumentService.definition(defParams).get(10, TimeUnit.SECONDS)
    }
    assertNotNull("Definition should return a result", result)
    val locations = result.getLeft.asScala
    assertFalse("Definition should return locations", locations.isEmpty)
    assertEquals("Definition should point to val declaration", 1, locations.head.getRange.getStart.getLine)

  def testDocumentSymbolViaWire(): Unit =
    doInitialize()

    val uri = configureScalaFile(
      """object Main:
        |  def foo = 42
        |  val bar = "hello"
        |""".stripMargin
    )
    openFile(uri, myFixture.getFile.getText)

    val result = requestOffEdt() {
      val symParams = new DocumentSymbolParams()
      symParams.setTextDocument(new TextDocumentIdentifier(uri))
      clientProxy.getTextDocumentService.documentSymbol(symParams).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    assertFalse("Should return document symbols", result.isEmpty)

  def testReferencesViaWire(): Unit =
    doInitialize()

    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def a = x
        |  def b = x
        |""".stripMargin
    )
    openFile(uri, myFixture.getFile.getText)

    val result = requestOffEdt() {
      val refParams = new ReferenceParams()
      refParams.setTextDocument(new TextDocumentIdentifier(uri))
      refParams.setPosition(new Position(1, 6))
      refParams.setContext(new ReferenceContext(false))
      clientProxy.getTextDocumentService.references(refParams).get(10, TimeUnit.SECONDS)
    }
    assertNotNull("References should return a result", result)

  def testFoldingRangeViaWire(): Unit =
    doInitialize()

    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Unit =
        |    val x = 1
        |    val y = 2
        |    println(x + y)
        |""".stripMargin
    )
    openFile(uri, myFixture.getFile.getText)

    val result = requestOffEdt() {
      val params = new FoldingRangeRequestParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      clientProxy.getTextDocumentService.foldingRange(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
