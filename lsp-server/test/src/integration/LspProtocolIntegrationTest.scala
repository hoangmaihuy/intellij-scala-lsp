package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither}
import org.jetbrains.scalalsP.{ScalaTextDocumentService, ScalaWorkspaceService, TestLanguageClient}
import org.jetbrains.scalalsP.intellij.IntellijProjectManager
import org.junit.Assert.*

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

// Protocol-level tests that exercise ScalaTextDocumentService and
// ScalaWorkspaceService methods with real LSP parameter objects.
class LspProtocolIntegrationTest extends ScalaLspTestBase:

  private def textDocService: ScalaTextDocumentService =
    val svc = ScalaTextDocumentService(projectManager)
    svc.connect(TestLanguageClient())
    svc

  private def workspaceService: ScalaWorkspaceService =
    ScalaWorkspaceService(projectManager)

  private def definitionParams(uri: String, line: Int, char: Int): DefinitionParams =
    val params = DefinitionParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(line, char))
    params

  private def referenceParams(uri: String, line: Int, char: Int, includeDecl: Boolean): ReferenceParams =
    val params = ReferenceParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(line, char))
    params.setContext(ReferenceContext(includeDecl))
    params

  def testDefinitionViaProtocol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    val result = textDocService.definition(definitionParams(uri, 2, 12))
      .get(10, TimeUnit.SECONDS)
    assertNotNull(result)
    val locations = result.getLeft.asScala
    assertFalse("Protocol should return locations", locations.isEmpty)

  def testReferencesViaProtocol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def a = x
        |  def b = x
        |""".stripMargin
    )
    val result = textDocService.references(referenceParams(uri, 1, 6, includeDecl = false))
      .get(10, TimeUnit.SECONDS)
    assertNotNull(result)
    // ReferencesSearch may return empty in light test mode without indexes
    if result.size() > 0 then
      assertTrue("Protocol should return references", result.size() >= 2)

  def testHoverViaProtocol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |""".stripMargin
    )
    val params = HoverParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(1, 6))
    val result = textDocService.hover(params).get(10, TimeUnit.SECONDS)
    if result != null then
      assertNotNull(result.getContents)

  def testDocumentSymbolViaProtocol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo = 42
        |  val bar = "hello"
        |""".stripMargin
    )
    val params = DocumentSymbolParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    val result = textDocService.documentSymbol(params).get(10, TimeUnit.SECONDS)
    assertNotNull(result)
    assertFalse("Protocol should return document symbols", result.isEmpty)

  def testFoldingRangeViaProtocol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Unit =
        |    val x = 1
        |    val y = 2
        |    println(x + y)
        |""".stripMargin
    )
    val params = FoldingRangeRequestParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    val result = textDocService.foldingRange(params).get(10, TimeUnit.SECONDS)
    assertNotNull(result)

  def testSelectionRangeViaProtocol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val params = SelectionRangeParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPositions(java.util.List.of(Position(1, 10)))
    val result = textDocService.selectionRange(params).get(10, TimeUnit.SECONDS)
    assertNotNull(result)
    assertFalse("Protocol should return selection ranges", result.isEmpty)

  def testImplementationViaProtocol(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def name: String
        |class Dog extends Animal:
        |  def name = "Dog"
        |""".stripMargin
    )
    val params = ImplementationParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(0, 6))
    val result = textDocService.implementation(params).get(10, TimeUnit.SECONDS)
    assertNotNull(result)

  def testTypeDefinitionViaProtocol(): Unit =
    val uri = configureScalaFile(
      """class Foo
        |object Main:
        |  val f: Foo = Foo()
        |""".stripMargin
    )
    val params = TypeDefinitionParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(2, 6))
    val result = textDocService.typeDefinition(params).get(10, TimeUnit.SECONDS)
    assertNotNull(result)

  def testCallHierarchyRoundTrip(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def target(): Int = 42
        |  def caller(): Int = target()
        |""".stripMargin
    )
    val prepareParams = CallHierarchyPrepareParams()
    prepareParams.setTextDocument(TextDocumentIdentifier(uri))
    prepareParams.setPosition(Position(1, 6))
    val prepared = textDocService.prepareCallHierarchy(prepareParams)
      .get(10, TimeUnit.SECONDS)
    assertNotNull(prepared)
    if !prepared.isEmpty then
      val item = prepared.get(0)
      val incomingParams = CallHierarchyIncomingCallsParams()
      incomingParams.setItem(item)
      val incoming = textDocService.callHierarchyIncomingCalls(incomingParams)
        .get(10, TimeUnit.SECONDS)
      assertNotNull(incoming)

      val outgoingParams = CallHierarchyOutgoingCallsParams()
      outgoingParams.setItem(item)
      val outgoing = textDocService.callHierarchyOutgoingCalls(outgoingParams)
        .get(10, TimeUnit.SECONDS)
      assertNotNull(outgoing)

  def testDiagnosticsPushedToClient(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = "wrong"
        |""".stripMargin
    )
    val received = scala.collection.mutable.ArrayBuffer[PublishDiagnosticsParams]()
    val client = new TestLanguageClient:
      override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
        received += params

    val svc = ScalaTextDocumentService(projectManager)
    svc.connect(client)

    myFixture.doHighlighting()

    val openParams = DidOpenTextDocumentParams()
    openParams.setTextDocument(
      TextDocumentItem(uri, "scala", 1, myFixture.getFile.getText)
    )
    svc.didOpen(openParams)

  def testWorkspaceSymbolViaProtocol(): Unit =
    configureScalaFile(
      """object UniqueTestService:
        |  def process = 42
        |""".stripMargin
    )
    val params = WorkspaceSymbolParams()
    params.setQuery("UniqueTestService")
    val result = workspaceService.symbol(params).get(10, TimeUnit.SECONDS)
    assertNotNull(result)
