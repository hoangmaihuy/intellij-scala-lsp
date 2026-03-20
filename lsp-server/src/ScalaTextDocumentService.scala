package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither, Either3 as LspEither3}
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
  private val inlayHintProvider = InlayHintProvider(projectManager)
  private val completionProvider = CompletionProvider(projectManager)
  private val codeActionProvider = CodeActionProvider(projectManager)
  private val renameProvider = RenameProvider(projectManager)
  private val typeHierarchyProvider = TypeHierarchyProvider(projectManager)
  private val formattingProvider = FormattingProvider(projectManager)

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

  // --- Inlay Hints ---

  override def inlayHint(params: InlayHintParams): CompletableFuture[util.List[InlayHint]] =
    CompletableFuture.supplyAsync: () =>
      inlayHintProvider.getInlayHints(
        params.getTextDocument.getUri,
        params.getRange
      ).asJava

  // --- Completion ---

  override def completion(params: CompletionParams): CompletableFuture[LspEither[util.List[CompletionItem], CompletionList]] =
    CompletableFuture.supplyAsync: () =>
      val items = completionProvider.getCompletions(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(items.asJava)

  override def resolveCompletionItem(unresolved: CompletionItem): CompletableFuture[CompletionItem] =
    CompletableFuture.supplyAsync: () =>
      completionProvider.resolveCompletion(unresolved)

  // --- Code Actions ---

  override def codeAction(params: CodeActionParams): CompletableFuture[util.List[LspEither[Command, CodeAction]]] =
    CompletableFuture.supplyAsync: () =>
      codeActionProvider.getCodeActions(
        params.getTextDocument.getUri,
        params.getRange,
        params.getContext
      ).map(ca => LspEither.forRight[Command, CodeAction](ca)).asJava

  // --- Rename ---

  override def prepareRename(params: PrepareRenameParams): CompletableFuture[LspEither3[Range, PrepareRenameResult, PrepareRenameDefaultBehavior]] =
    CompletableFuture.supplyAsync: () =>
      val result = renameProvider.prepareRename(
        params.getTextDocument.getUri,
        params.getPosition
      )
      if result != null then LspEither3.forSecond(result)
      else null

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    CompletableFuture.supplyAsync: () =>
      renameProvider.rename(
        params.getTextDocument.getUri,
        params.getPosition,
        params.getNewName
      )

  // --- Type Hierarchy ---

  override def prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      typeHierarchyProvider.prepare(
        params.getTextDocument.getUri,
        params.getPosition
      ).asJava

  override def typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      typeHierarchyProvider.supertypes(params.getItem).asJava

  override def typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      typeHierarchyProvider.subtypes(params.getItem).asJava

  // --- Formatting ---

  override def formatting(params: DocumentFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    CompletableFuture.supplyAsync: () =>
      formattingProvider.getFormatting(params.getTextDocument.getUri).asJava

  override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    CompletableFuture.supplyAsync: () =>
      formattingProvider.getRangeFormatting(
        params.getTextDocument.getUri,
        params.getRange
      ).asJava
