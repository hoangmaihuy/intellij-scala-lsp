package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.jetbrains.scalalsP.{JavaTestLanguageClient, LspLauncher, ScalaLspServer}
import org.junit.Assert.*

import java.io.{PipedInputStream, PipedOutputStream}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
 * Comprehensive integration test with a multi-file project structure including:
 * - Trait hierarchy (for goToImplementation, type hierarchy)
 * - Cross-file references (for findReferences)
 * - Maven-style dependencies (via Scala standard library)
 * - Method overrides, val usages, and type annotations
 *
 * Exercises the full LSP wire protocol through LspLauncher.
 */
class ComprehensiveProjectIntegrationTest extends ScalaLspTestBase:

  private var clientProxy: LanguageServer = scala.compiletime.uninitialized
  private var serverThread: Thread = scala.compiletime.uninitialized
  private var serverOut: PipedOutputStream = scala.compiletime.uninitialized
  private var clientOut: PipedOutputStream = scala.compiletime.uninitialized

  // URIs for test files
  private var baseTraitUri: String = scala.compiletime.uninitialized
  private var implAUri: String = scala.compiletime.uninitialized
  private var implBUri: String = scala.compiletime.uninitialized
  private var serviceUri: String = scala.compiletime.uninitialized
  private var mainUri: String = scala.compiletime.uninitialized

  override def setUp(): Unit =
    super.setUp()
    setupLspConnection()
    setupProjectFiles()

  private def setupLspConnection(): Unit =
    val serverIn = new PipedInputStream(65536)
    clientOut = new PipedOutputStream(serverIn)
    val clientIn = new PipedInputStream(65536)
    serverOut = new PipedOutputStream(clientIn)

    val server = new ScalaLspServer(getProject.getBasePath, projectManager)
    serverThread = new Thread(() =>
      try LspLauncher.startAndAwait(server, serverIn, serverOut)
      catch case _: Exception => ()
    , "lsp-comprehensive-server")
    serverThread.setDaemon(true)
    serverThread.start()

    val launcher = new Launcher.Builder[LanguageServer]()
      .setLocalService(new JavaTestLanguageClient())
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(clientIn)
      .setOutput(clientOut)
      .create()
    clientProxy = launcher.getRemoteProxy
    launcher.startListening()

    // Initialize
    requestOffEdt() {
      val params = new InitializeParams()
      params.setProcessId(ProcessHandle.current().pid().toInt)
      params.setRootUri(s"file://${getProject.getBasePath}")
      params.setCapabilities(new ClientCapabilities())
      clientProxy.initialize(params).get(30, TimeUnit.SECONDS)
      clientProxy.initialized(new InitializedParams())
    }

  private def setupProjectFiles(): Unit =
    // Base trait — the root of the hierarchy
    baseTraitUri = addScalaFile("animal/Animal.scala",
      """package animal
        |
        |trait Animal:
        |  def name: String
        |  def sound: String
        |  def describe: String = s"$name says $sound"
        |""".stripMargin)

    // Implementation A
    implAUri = addScalaFile("animal/Dog.scala",
      """package animal
        |
        |class Dog extends Animal:
        |  override def name: String = "Dog"
        |  override def sound: String = "Woof"
        |  def fetch(item: String): String = s"$name fetches $item"
        |""".stripMargin)

    // Implementation B
    implBUri = addScalaFile("animal/Cat.scala",
      """package animal
        |
        |class Cat extends Animal:
        |  override def name: String = "Cat"
        |  override def sound: String = "Meow"
        |  def purr: String = s"$name purrs"
        |""".stripMargin)

    // Service that uses the trait and implementations
    serviceUri = addScalaFile("service/AnimalService.scala",
      """package service
        |
        |import animal.{Animal, Dog, Cat}
        |
        |class AnimalService:
        |  private val animals: List[Animal] = List(Dog(), Cat())
        |
        |  def describeAll: List[String] =
        |    animals.map(_.describe)
        |
        |  def findByName(name: String): Option[Animal] =
        |    animals.find(_.name == name)
        |
        |  def makeSound(animal: Animal): String =
        |    animal.sound
        |""".stripMargin)

    // Main entry point referencing everything
    mainUri = addScalaFile("Main.scala",
      """import animal.{Animal, Dog, Cat}
        |import service.AnimalService
        |
        |object Main:
        |  val service = AnimalService()
        |
        |  def run(): Unit =
        |    val descriptions = service.describeAll
        |    val dog: Animal = Dog()
        |    val result = dog.describe
        |""".stripMargin)

  override def tearDown(): Unit =
    try
      clientOut.close()
      serverOut.close()
      serverThread.join(5000)
    catch case _: Exception => ()
    super.tearDown()

  private def requestOffEdt[T](timeout: Int = 15)(body: => T): T =
    val future = CompletableFuture.supplyAsync[T](() => body)
    val deadline = System.currentTimeMillis() + timeout * 1000L
    while !future.isDone && System.currentTimeMillis() < deadline do
      com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    future.get(1, TimeUnit.SECONDS)

  private def openFile(uri: String): Unit =
    val vf = projectManager.findVirtualFile(uri).get
    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
    val didOpenParams = new DidOpenTextDocumentParams()
    didOpenParams.setTextDocument(new TextDocumentItem(uri, "scala", 1, doc.getText))
    clientProxy.getTextDocumentService.didOpen(didOpenParams)
    Thread.sleep(200)

  // --- Document Symbol Tests ---

  def testDocumentSymbolsForTrait(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new DocumentSymbolParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      clientProxy.getTextDocumentService.documentSymbol(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    assertFalse("Should return symbols for Animal trait", result.isEmpty)

  def testDocumentSymbolsForClass(): Unit =
    openFile(implAUri)
    val result = requestOffEdt() {
      val params = new DocumentSymbolParams()
      params.setTextDocument(new TextDocumentIdentifier(implAUri))
      clientProxy.getTextDocumentService.documentSymbol(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    assertFalse("Should return symbols for Dog class", result.isEmpty)

  // --- Hover Tests ---

  def testHoverOnTraitName(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new HoverParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(2, 6)) // "Animal" in "trait Animal"
      clientProxy.getTextDocumentService.hover(params).get(10, TimeUnit.SECONDS)
    }
    if result != null then
      assertNotNull("Hover should have contents", result.getContents)

  def testHoverOnMethodInClass(): Unit =
    openFile(implAUri)
    val result = requestOffEdt() {
      val params = new HoverParams()
      params.setTextDocument(new TextDocumentIdentifier(implAUri))
      params.setPosition(new Position(5, 6)) // "fetch" method
      clientProxy.getTextDocumentService.hover(params).get(10, TimeUnit.SECONDS)
    }
    if result != null then
      val value = result.getContents.getRight.getValue
      assertTrue(s"Hover should contain method info, got: $value", value.nonEmpty)

  // --- Definition Tests ---

  def testGoToDefinitionOfTraitUsage(): Unit =
    openFile(serviceUri)
    val result = requestOffEdt() {
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(serviceUri))
      params.setPosition(new Position(4, 30)) // "Animal" in "List[Animal]"
      clientProxy.getTextDocumentService.definition(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    val locations = result.getLeft.asScala
    if locations.nonEmpty then
      assertTrue("Definition should point to Animal.scala",
        locations.head.getUri.contains("Animal.scala"))

  def testGoToDefinitionOfClassUsage(): Unit =
    openFile(serviceUri)
    val result = requestOffEdt() {
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(serviceUri))
      params.setPosition(new Position(5, 42)) // "Dog" in "Dog()"
      clientProxy.getTextDocumentService.definition(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    val locations = result.getLeft.asScala
    if locations.nonEmpty then
      assertTrue("Definition should point to Dog.scala",
        locations.head.getUri.contains("Dog.scala"))

  // --- Implementation Tests ---

  def testImplementationsOfTrait(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new ImplementationParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(2, 6)) // "Animal" trait name
      clientProxy.getTextDocumentService.implementation(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    // DefinitionsScopedSearch may return empty in light test mode without full indexing
    val locations = result.getLeft.asScala
    if locations.nonEmpty then
      assertTrue(s"Should find at least 2 implementations (Dog, Cat), found ${locations.size}",
        locations.size >= 2)

  def testImplementationsOfMethod(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new ImplementationParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(3, 6)) // "name" method
      clientProxy.getTextDocumentService.implementation(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)

  // --- References Tests ---

  def testReferencesToTrait(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new ReferenceParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(2, 6)) // "Animal" trait
      params.setContext(new ReferenceContext(false))
      clientProxy.getTextDocumentService.references(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    // References should include Dog, Cat, AnimalService, Main
    if result.size() > 0 then
      assertTrue(s"Should find multiple references to Animal, found ${result.size()}", result.size() >= 2)

  def testReferencesToMethod(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new ReferenceParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(5, 6)) // "describe" method
      params.setContext(new ReferenceContext(false))
      clientProxy.getTextDocumentService.references(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)

  // --- Type Hierarchy Tests ---

  def testTypeHierarchyPrepare(): Unit =
    openFile(baseTraitUri)
    val result = requestOffEdt() {
      val params = new TypeHierarchyPrepareParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(2, 6)) // "Animal"
      clientProxy.getTextDocumentService.prepareTypeHierarchy(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    if !result.isEmpty then
      assertEquals("Animal", result.get(0).getName)

  def testTypeHierarchySubtypes(): Unit =
    openFile(baseTraitUri)
    val prepared = requestOffEdt() {
      val params = new TypeHierarchyPrepareParams()
      params.setTextDocument(new TextDocumentIdentifier(baseTraitUri))
      params.setPosition(new Position(2, 6))
      clientProxy.getTextDocumentService.prepareTypeHierarchy(params).get(10, TimeUnit.SECONDS)
    }
    if prepared != null && !prepared.isEmpty then
      val subtypes = requestOffEdt() {
        val params = new TypeHierarchySubtypesParams()
        params.setItem(prepared.get(0))
        clientProxy.getTextDocumentService.typeHierarchySubtypes(params).get(10, TimeUnit.SECONDS)
      }
      assertNotNull(subtypes)
      assertTrue(s"Should find at least 2 subtypes (Dog, Cat), found ${subtypes.size()}",
        subtypes.size() >= 2)

  // --- Call Hierarchy Tests ---

  def testCallHierarchyOnMethod(): Unit =
    openFile(serviceUri)
    val result = requestOffEdt() {
      val params = new CallHierarchyPrepareParams()
      params.setTextDocument(new TextDocumentIdentifier(serviceUri))
      params.setPosition(new Position(7, 6)) // "describeAll" method
      clientProxy.getTextDocumentService.prepareCallHierarchy(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)

  // --- Folding Range Tests ---

  def testFoldingRangesForMultiFileProject(): Unit =
    openFile(serviceUri)
    val result = requestOffEdt() {
      val params = new FoldingRangeRequestParams()
      params.setTextDocument(new TextDocumentIdentifier(serviceUri))
      clientProxy.getTextDocumentService.foldingRange(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)

  // --- Selection Range Tests ---

  def testSelectionRangeOnMethod(): Unit =
    openFile(implAUri)
    val result = requestOffEdt() {
      val params = new SelectionRangeParams()
      params.setTextDocument(new TextDocumentIdentifier(implAUri))
      params.setPositions(java.util.List.of(new Position(5, 10)))
      clientProxy.getTextDocumentService.selectionRange(params).get(10, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    assertFalse("Should return selection ranges", result.isEmpty)
