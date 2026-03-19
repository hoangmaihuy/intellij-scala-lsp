package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither}
import org.eclipse.lsp4j.services.{LanguageClient, TextDocumentService}
import org.jetbrains.scalalsP.intellij.*

import java.util
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

// Handles all textDocument LSP requests by delegating to IntelliJ-backed providers.
class ScalaTextDocumentService(projectManager: IntellijProjectManager) extends TextDocumentService:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val documentSync = DocumentSyncManager(projectManager)
  private val definitionProvider = DefinitionProvider(projectManager)
  private val referencesProvider = ReferencesProvider(projectManager)
  private val hoverProvider = HoverProvider(projectManager)
  private val symbolProvider = SymbolProvider(projectManager)
  private val typeDefinitionProvider = TypeDefinitionProvider(projectManager)
  private val implementationProvider = ImplementationProvider(projectManager)
  private val diagnosticsProvider = DiagnosticsProvider(projectManager)
  private val foldingRangeProvider = FoldingRangeProvider(projectManager)
  private val selectionRangeProvider = SelectionRangeProvider(projectManager)
  private val callHierarchyProvider = CallHierarchyProvider(projectManager)

  def connect(client: LanguageClient): Unit =
    this.client = client
    diagnosticsProvider.connect(client)

  def registerDaemonListener(): Unit =
    diagnosticsProvider.registerDaemonListener()

  // --- Document Synchronization ---

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    documentSync.didOpen(uri, params.getTextDocument.getText)
    diagnosticsProvider.trackOpen(uri)

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    val changes = params.getContentChanges.asScala.toSeq
    changes.headOption.foreach: change =>
      documentSync.didChange(uri, change.getText)

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    documentSync.didClose(uri)
    diagnosticsProvider.trackClose(uri)

  override def didSave(params: DidSaveTextDocumentParams): Unit =
    documentSync.didSave(params.getTextDocument.getUri)

  // --- Navigation ---

  override def definition(params: DefinitionParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    CompletableFuture.supplyAsync: () =>
      val locations = definitionProvider.getDefinition(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(locations.asJava)

  override def references(params: ReferenceParams): CompletableFuture[util.List[? <: Location]] =
    CompletableFuture.supplyAsync: () =>
      referencesProvider.findReferences(
        params.getTextDocument.getUri,
        params.getPosition,
        params.getContext.isIncludeDeclaration
      ).asJava

  override def hover(params: HoverParams): CompletableFuture[Hover] =
    CompletableFuture.supplyAsync: () =>
      hoverProvider.getHover(
        params.getTextDocument.getUri,
        params.getPosition
      ).orNull

  override def typeDefinition(params: TypeDefinitionParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    CompletableFuture.supplyAsync: () =>
      val locations = typeDefinitionProvider.getTypeDefinition(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(locations.asJava)

  override def implementation(params: ImplementationParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    CompletableFuture.supplyAsync: () =>
      val locations = implementationProvider.getImplementations(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(locations.asJava)

  override def documentSymbol(params: DocumentSymbolParams): CompletableFuture[util.List[LspEither[SymbolInformation, DocumentSymbol]]] =
    CompletableFuture.supplyAsync: () =>
      symbolProvider.documentSymbols(params.getTextDocument.getUri)
        .map(ds => LspEither.forRight[SymbolInformation, DocumentSymbol](ds))
        .asJava

  override def foldingRange(params: FoldingRangeRequestParams): CompletableFuture[util.List[FoldingRange]] =
    CompletableFuture.supplyAsync: () =>
      foldingRangeProvider.getFoldingRanges(params.getTextDocument.getUri).asJava

  override def selectionRange(params: SelectionRangeParams): CompletableFuture[util.List[SelectionRange]] =
    CompletableFuture.supplyAsync: () =>
      val positions = params.getPositions.asScala.toSeq
      selectionRangeProvider.getSelectionRanges(params.getTextDocument.getUri, positions).asJava

  // --- Call Hierarchy ---

  override def prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture[util.List[CallHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      callHierarchyProvider.prepare(
        params.getTextDocument.getUri,
        params.getPosition
      ).asJava

  override def callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    CompletableFuture.supplyAsync: () =>
      callHierarchyProvider.incomingCalls(params.getItem).asJava

  override def callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    CompletableFuture.supplyAsync: () =>
      callHierarchyProvider.outgoingCalls(params.getItem).asJava
