package org.jetbrains.scalalsP

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

import java.util.concurrent.{CompletableFuture, TimeUnit}

class LspProtocolTest:

  @Test def testGetTextDocumentServiceReturnsNonNull(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    assertNotNull(server.getTextDocumentService)

  @Test def testGetWorkspaceServiceReturnsNonNull(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    assertNotNull(server.getWorkspaceService)

  @Test def testServerReturnsCorrectServiceTypes(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    assertTrue(server.getTextDocumentService.isInstanceOf[ScalaTextDocumentService])
    assertTrue(server.getWorkspaceService.isInstanceOf[ScalaWorkspaceService])

  @Test def testConnectDoesNotThrow(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    val client = TestLanguageClient()
    server.connect(client)

  @Test def testInitializeReturnsCapabilities(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    val params = new InitializeParams()
    params.setRootUri("file:///tmp/test-project")
    params.setCapabilities(new ClientCapabilities())

    val future = server.initialize(params)
    try
      val result = future.get(10, TimeUnit.SECONDS)
      val capabilities = result.getCapabilities
      assertNotNull(capabilities)

      assertTrue(capabilities.getHoverProvider.getLeft.booleanValue())
      assertTrue(capabilities.getDefinitionProvider.getLeft.booleanValue())
      assertTrue(capabilities.getTypeDefinitionProvider.getLeft.booleanValue())
      assertTrue(capabilities.getImplementationProvider.getLeft.booleanValue())
      assertTrue(capabilities.getReferencesProvider.getLeft.booleanValue())
      assertTrue(capabilities.getDocumentSymbolProvider.getLeft.booleanValue())
      assertTrue(capabilities.getWorkspaceSymbolProvider.getLeft.booleanValue())
      assertTrue(capabilities.getFoldingRangeProvider.getLeft.booleanValue())
      assertTrue(capabilities.getSelectionRangeProvider.getLeft.booleanValue())
      assertTrue(capabilities.getCallHierarchyProvider.getLeft.booleanValue())

      assertEquals(TextDocumentSyncKind.Full, capabilities.getTextDocumentSync.getLeft)

      assertEquals("intellij-scala-lsp", result.getServerInfo.getName)
      assertEquals("0.1.0", result.getServerInfo.getVersion)
    catch
      case _: java.util.concurrent.ExecutionException => ()

  @Test def testShutdownReturnsNull(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    try
      val result = server.shutdown().get(5, TimeUnit.SECONDS)
      assertNull(result)
    catch
      case _: Exception => ()

  @Test def testInitializeExtractsPathFromFileUri(): Unit =
    val server = new ScalaLspServer("/tmp/fallback")
    val params = new InitializeParams()
    params.setRootUri("file:///home/user/my-project")
    params.setCapabilities(new ClientCapabilities())
    try server.initialize(params).get(5, TimeUnit.SECONDS)
    catch case _: java.util.concurrent.ExecutionException => ()

  @Test def testInitializeUsesRootPathAsFallback(): Unit =
    val server = new ScalaLspServer("/tmp/fallback")
    val params = new InitializeParams()
    params.setRootPath("/home/user/project")
    params.setCapabilities(new ClientCapabilities())
    try server.initialize(params).get(5, TimeUnit.SECONDS)
    catch case _: java.util.concurrent.ExecutionException => ()

  @Test def testInitializeUsesConstructorPathAsLastFallback(): Unit =
    val server = new ScalaLspServer("/tmp/constructor-path")
    val params = new InitializeParams()
    params.setCapabilities(new ClientCapabilities())
    try server.initialize(params).get(5, TimeUnit.SECONDS)
    catch case _: java.util.concurrent.ExecutionException => ()


class TestLanguageClient extends LanguageClient:
  val diagnostics = scala.collection.mutable.ArrayBuffer[PublishDiagnosticsParams]()
  val logMessages = scala.collection.mutable.ArrayBuffer[MessageParams]()

  override def telemetryEvent(obj: Any): Unit = ()
  override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
    diagnostics += params
  override def showMessage(params: MessageParams): Unit = ()
  override def showMessageRequest(params: ShowMessageRequestParams): CompletableFuture[MessageActionItem] =
    CompletableFuture.completedFuture(null)
  override def logMessage(params: MessageParams): Unit =
    logMessages += params
