package org.jetbrains.scalalsP.e2e

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.jetbrains.scalalsP.{JavaTestLanguageClient, LspLauncher, ScalaLspServer}

import java.io.{PipedInputStream, PipedOutputStream}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.jdk.CollectionConverters.*

class TestLspClient private (
  clientProxy: LanguageServer,
  cleanup: () => Unit,
  diagnosticsClient: DiagnosticsCollectingClient,
  hasEdt: Boolean = true
):

  private var versionCounter = 0

  // --- Lifecycle ---

  def initialize(rootUri: String): InitializeResult =
    requestOffEdt() {
      val params = new InitializeParams()
      params.setProcessId(ProcessHandle.current().pid().toInt)
      params.setRootUri(rootUri)
      params.setCapabilities(new ClientCapabilities())
      val result = clientProxy.initialize(params).get(30, TimeUnit.SECONDS)
      clientProxy.initialized(new InitializedParams())
      result
    }

  def shutdown(): Unit =
    try
      requestOffEdt(5) {
        clientProxy.shutdown().get(5, TimeUnit.SECONDS)
      }
    catch case _: Exception => ()
    cleanup()

  // --- Document Management ---

  def openFile(uri: String, content: String): Unit =
    versionCounter += 1
    val params = new DidOpenTextDocumentParams()
    params.setTextDocument(new TextDocumentItem(uri, "scala", versionCounter, content))
    clientProxy.getTextDocumentService.didOpen(params)
    pumpAndWait(500)

  def changeFile(uri: String, newContent: String): Unit =
    versionCounter += 1
    val params = new DidChangeTextDocumentParams()
    val id = new VersionedTextDocumentIdentifier()
    id.setUri(uri)
    id.setVersion(versionCounter)
    params.setTextDocument(id)
    val change = new TextDocumentContentChangeEvent()
    change.setText(newContent)
    params.setContentChanges(java.util.List.of(change))
    clientProxy.getTextDocumentService.didChange(params)
    pumpAndWait(300)

  def closeFile(uri: String): Unit =
    val params = new DidCloseTextDocumentParams()
    params.setTextDocument(new TextDocumentIdentifier(uri))
    clientProxy.getTextDocumentService.didClose(params)

  // --- Navigation ---

  def definition(uri: String, line: Int, char: Int): List[Location] =
    requestOffEdt() {
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      val either = clientProxy.getTextDocumentService.definition(params).get(10, TimeUnit.SECONDS)
      val locations: List[Location] = if either == null then Nil else either.getLeft.asScala.toList
      locations
    }

  def typeDefinition(uri: String, line: Int, char: Int): List[Location] =
    requestOffEdt() {
      val params = new TypeDefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      val either = clientProxy.getTextDocumentService.typeDefinition(params).get(10, TimeUnit.SECONDS)
      val locations: List[Location] = if either == null then Nil else either.getLeft.asScala.toList
      locations
    }

  def implementation(uri: String, line: Int, char: Int): List[Location] =
    requestOffEdt() {
      val params = new ImplementationParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      val either = clientProxy.getTextDocumentService.implementation(params).get(10, TimeUnit.SECONDS)
      val locations: List[Location] = if either == null then Nil else either.getLeft.asScala.toList
      locations
    }

  def references(uri: String, line: Int, char: Int, includeDecl: Boolean = false): List[Location] =
    requestOffEdt() {
      val params = new ReferenceParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      params.setContext(new ReferenceContext(includeDecl))
      val result = clientProxy.getTextDocumentService.references(params).get(10, TimeUnit.SECONDS)
      val locations: List[Location] = if result == null then Nil else result.asScala.toList
      locations
    }

  def hover(uri: String, line: Int, char: Int): Option[Hover] =
    requestOffEdt() {
      val params = new HoverParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      Option(clientProxy.getTextDocumentService.hover(params).get(10, TimeUnit.SECONDS))
    }

  // --- Hierarchy ---

  def prepareTypeHierarchy(uri: String, line: Int, char: Int): List[TypeHierarchyItem] =
    requestOffEdt() {
      val params = new TypeHierarchyPrepareParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      val result = clientProxy.getTextDocumentService.prepareTypeHierarchy(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def supertypes(item: TypeHierarchyItem): List[TypeHierarchyItem] =
    requestOffEdt() {
      val params = new TypeHierarchySupertypesParams()
      params.setItem(item)
      val result = clientProxy.getTextDocumentService.typeHierarchySupertypes(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def subtypes(item: TypeHierarchyItem): List[TypeHierarchyItem] =
    requestOffEdt() {
      val params = new TypeHierarchySubtypesParams()
      params.setItem(item)
      val result = clientProxy.getTextDocumentService.typeHierarchySubtypes(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def prepareCallHierarchy(uri: String, line: Int, char: Int): List[CallHierarchyItem] =
    requestOffEdt() {
      val params = new CallHierarchyPrepareParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      val result = clientProxy.getTextDocumentService.prepareCallHierarchy(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def incomingCalls(item: CallHierarchyItem): List[CallHierarchyIncomingCall] =
    requestOffEdt() {
      val params = new CallHierarchyIncomingCallsParams()
      params.setItem(item)
      val result = clientProxy.getTextDocumentService.callHierarchyIncomingCalls(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def outgoingCalls(item: CallHierarchyItem): List[CallHierarchyOutgoingCall] =
    requestOffEdt() {
      val params = new CallHierarchyOutgoingCallsParams()
      params.setItem(item)
      val result = clientProxy.getTextDocumentService.callHierarchyOutgoingCalls(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  // --- Code Intelligence ---

  def completion(uri: String, line: Int, char: Int): List[CompletionItem] =
    requestOffEdt() {
      val params = new CompletionParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      val result = clientProxy.getTextDocumentService.completion(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil
      else if result.isLeft then result.getLeft.asScala.toList
      else result.getRight.getItems.asScala.toList
    }

  def documentSymbols(uri: String): List[DocumentSymbol] =
    requestOffEdt() {
      val params = new DocumentSymbolParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      val result = clientProxy.getTextDocumentService.documentSymbol(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil
      else result.asScala.map(_.getRight).toList
    }

  def workspaceSymbol(query: String): List[WorkspaceSymbol] =
    requestOffEdt() {
      val params = new WorkspaceSymbolParams(query)
      val either = clientProxy.getWorkspaceService.symbol(params).get(10, TimeUnit.SECONDS)
      val symbols: List[WorkspaceSymbol] = if either == null then Nil else either.getRight.asScala.toList
      symbols
    }

  def inlayHints(uri: String, startLine: Int, endLine: Int): List[InlayHint] =
    requestOffEdt() {
      val params = new InlayHintParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setRange(new Range(new Position(startLine, 0), new Position(endLine, 0)))
      val result = clientProxy.getTextDocumentService.inlayHint(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def foldingRanges(uri: String): List[FoldingRange] =
    requestOffEdt() {
      val params = new FoldingRangeRequestParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      val result = clientProxy.getTextDocumentService.foldingRange(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  def selectionRanges(uri: String, positions: List[(Int, Int)]): List[SelectionRange] =
    requestOffEdt() {
      val params = new SelectionRangeParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPositions(positions.map((l, c) => new Position(l, c)).asJava)
      val result = clientProxy.getTextDocumentService.selectionRange(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }

  // --- Editing ---

  def rename(uri: String, line: Int, char: Int, newName: String): WorkspaceEdit =
    requestOffEdt() {
      val params = new RenameParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      params.setNewName(newName)
      clientProxy.getTextDocumentService.rename(params).get(10, TimeUnit.SECONDS)
    }

  def codeActions(uri: String, startLine: Int, startChar: Int, endLine: Int, endChar: Int): List[CodeAction] =
    requestOffEdt() {
      val params = new CodeActionParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setRange(new Range(new Position(startLine, startChar), new Position(endLine, endChar)))
      params.setContext(new CodeActionContext(java.util.List.of()))
      val result = clientProxy.getTextDocumentService.codeAction(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil
      else result.asScala.map(_.getRight).toList
    }

  def executeCommand(command: String, args: java.util.List[AnyRef]): AnyRef =
    requestOffEdt() {
      val params = new ExecuteCommandParams()
      params.setCommand(command)
      params.setArguments(args)
      clientProxy.getWorkspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    }

  // --- Diagnostics ---

  def awaitDiagnostics(uri: String, timeoutMs: Long = 5000): List[Diagnostic] =
    val deadline = System.currentTimeMillis() + timeoutMs
    while System.currentTimeMillis() < deadline do
      val diags = diagnosticsClient.getDiagnostics(uri)
      if diags.nonEmpty then return diags
      Thread.sleep(100)
    diagnosticsClient.getDiagnostics(uri)

  // --- Internal ---

  /** Pump EDT events while waiting, so server-side invokeAndWait can proceed. */
  private def pumpAndWait(ms: Long): Unit =
    if !hasEdt then
      Thread.sleep(ms)
      return
    val deadline = System.currentTimeMillis() + ms
    while System.currentTimeMillis() < deadline do
      try com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      catch case _: Exception => ()
      Thread.sleep(50)

  private def requestOffEdt[T](timeout: Int = 15)(body: => T): T =
    if !hasEdt then
      val future = CompletableFuture.supplyAsync[T](() => body)
      return future.get(timeout, TimeUnit.SECONDS)
    val future = CompletableFuture.supplyAsync[T](() => body)
    val deadline = System.currentTimeMillis() + timeout * 1000L
    while !future.isDone && System.currentTimeMillis() < deadline do
      try com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      catch case _: Exception => ()
      Thread.sleep(50)
    future.get(1, TimeUnit.SECONDS)


object TestLspClient:

  def inProcess(server: ScalaLspServer): TestLspClient =
    val serverIn = new PipedInputStream(65536)
    val clientOut = new PipedOutputStream(serverIn)
    val clientIn = new PipedInputStream(65536)
    val serverOut = new PipedOutputStream(clientIn)

    val serverThread = new Thread(() =>
      try LspLauncher.startAndAwait(server, serverIn, serverOut)
      catch case _: Exception => ()
    , "lsp-e2e-server")
    serverThread.setDaemon(true)
    serverThread.start()

    val diagnosticsClient = new DiagnosticsCollectingClient()

    val launcher = new Launcher.Builder[LanguageServer]()
      .setLocalService(diagnosticsClient)
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(clientIn)
      .setOutput(clientOut)
      .create()
    val proxy = launcher.getRemoteProxy
    launcher.startListening()

    val cleanup: () => Unit = () =>
      try
        clientOut.close()
        serverOut.close()
        serverThread.join(5000)
      catch case _: Exception => ()

    new TestLspClient(proxy, cleanup, diagnosticsClient)

  def subprocess(command: List[String]): TestLspClient =
    val process = new ProcessBuilder(command.asJava)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

    val diagnosticsClient = new DiagnosticsCollectingClient()

    val launcher = new Launcher.Builder[LanguageServer]()
      .setLocalService(diagnosticsClient)
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(process.getInputStream)
      .setOutput(process.getOutputStream)
      .create()
    val proxy = launcher.getRemoteProxy
    launcher.startListening()

    val cleanup: () => Unit = () =>
      try process.destroyForcibly()
      catch case _: Exception => ()

    new TestLspClient(proxy, cleanup, diagnosticsClient, hasEdt = false)


class DiagnosticsCollectingClient extends JavaTestLanguageClient:
  private val diagnosticsMap = scala.collection.concurrent.TrieMap[String, List[Diagnostic]]()

  override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
    diagnosticsMap.put(params.getUri, params.getDiagnostics.asScala.toList)

  def getDiagnostics(uri: String): List[Diagnostic] =
    diagnosticsMap.getOrElse(uri, Nil)

  def clearDiagnostics(): Unit =
    diagnosticsMap.clear()
