package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither, Either3 as LspEither3}
import org.eclipse.lsp4j.services.{LanguageClient, TextDocumentService}
import org.jetbrains.scalalsP.intellij.*

import java.util
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import scala.jdk.CollectionConverters.*

// Handles all textDocument LSP requests by delegating to IntelliJ-backed providers.
class ScalaTextDocumentService(projectManager: IntellijProjectManager, val diagnosticsProvider: DiagnosticsProvider) extends TextDocumentService:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized

  // Dedicated thread pool for LSP request handlers. Using the common ForkJoinPool causes deadlocks
  // when all threads block in smartReadAction (waiting for smart mode) or invokeAndWait (waiting for EDT).
  // A cached pool grows as needed and reclaims idle threads, preventing thread starvation.
  private val lspExecutor: ExecutorService = Executors.newCachedThreadPool: r =>
    val t = Thread(r, "lsp-request-handler")
    t.setDaemon(true)
    t
  /** Run a supplier on the dedicated LSP executor, returning a CompletableFuture. */
  private def supplyAsync[T](f: => T): CompletableFuture[T] =
    CompletableFuture.supplyAsync((() => f): java.util.function.Supplier[T], lspExecutor)

  private val documentSync = DocumentSyncManager(projectManager)
  private val definitionProvider = DefinitionProvider(projectManager)
  private val referencesProvider = ReferencesProvider(projectManager)
  private val definitionOrReferencesProvider = DefinitionOrReferencesProvider(projectManager, definitionProvider, referencesProvider)
  private val hoverProvider = HoverProvider(projectManager)
  private val symbolProvider = SymbolProvider(projectManager)
  private val typeDefinitionProvider = TypeDefinitionProvider(projectManager)
  private val implementationProvider = ImplementationProvider(projectManager)
  private val foldingRangeProvider = FoldingRangeProvider(projectManager)
  private val selectionRangeProvider = SelectionRangeProvider(projectManager)
  private val callHierarchyProvider = CallHierarchyProvider(projectManager)
  private val inlayHintProvider = InlayHintProvider(projectManager)
  private val completionProvider = CompletionProvider(projectManager)
  private val codeActionProvider = CodeActionProvider(projectManager)
  private val renameProvider = RenameProvider(projectManager)
  private val typeHierarchyProvider = TypeHierarchyProvider(projectManager)
  private val signatureHelpProvider = SignatureHelpProvider(projectManager)
  private val formattingProvider = FormattingProvider(projectManager)
  private val onTypeFormattingProvider = OnTypeFormattingProvider(projectManager)
  private val documentLinkProvider = DocumentLinkProvider(projectManager)
  private val semanticTokensProvider = SemanticTokensProvider(projectManager)
  private val documentHighlightProvider = DocumentHighlightProvider(projectManager)
  private val codeLensProvider = CodeLensProvider(projectManager, List(SuperMethodCodeLens()))

  def connect(client: LanguageClient): Unit =
    this.client = client
    diagnosticsProvider.connect(client)

  /** Get the last references result with usage types (for executeCommand access). */
  def getLastReferencesWithTypes: Seq[ReferenceResult] =
    referencesProvider.getLastResultsWithTypes

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
    diagnosticsProvider.scheduleAnalysis(uri) // debounced, 1s delay

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    documentSync.didClose(uri)
    diagnosticsProvider.trackClose(uri)

  override def didSave(params: DidSaveTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    documentSync.didSave(uri)
    diagnosticsProvider.scheduleAnalysis(uri, delayMs = 100) // analyze promptly on save

  // --- Navigation ---

  override def definition(params: DefinitionParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    supplyAsync:
      val locations = definitionOrReferencesProvider.getDefinitionOrReferences(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(locations.asJava)

  override def references(params: ReferenceParams): CompletableFuture[util.List[? <: Location]] =
    supplyAsync:
      referencesProvider.findReferences(
        params.getTextDocument.getUri,
        params.getPosition,
        params.getContext.isIncludeDeclaration
      ).asJava

  override def hover(params: HoverParams): CompletableFuture[Hover] =
    supplyAsync:
      hoverProvider.getHover(
        params.getTextDocument.getUri,
        params.getPosition
      ).orNull

  override def typeDefinition(params: TypeDefinitionParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    supplyAsync:
      val locations = typeDefinitionProvider.getTypeDefinition(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(locations.asJava)

  override def implementation(params: ImplementationParams): CompletableFuture[LspEither[util.List[? <: Location], util.List[? <: LocationLink]]] =
    supplyAsync:
      val locations = implementationProvider.getImplementations(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(locations.asJava)

  override def documentSymbol(params: DocumentSymbolParams): CompletableFuture[util.List[LspEither[SymbolInformation, DocumentSymbol]]] =
    supplyAsync:
      symbolProvider.documentSymbols(params.getTextDocument.getUri)
        .map(ds => LspEither.forRight[SymbolInformation, DocumentSymbol](ds))
        .asJava

  override def foldingRange(params: FoldingRangeRequestParams): CompletableFuture[util.List[FoldingRange]] =
    supplyAsync:
      foldingRangeProvider.getFoldingRanges(params.getTextDocument.getUri).asJava

  override def selectionRange(params: SelectionRangeParams): CompletableFuture[util.List[SelectionRange]] =
    supplyAsync:
      val positions = params.getPositions.asScala.toSeq
      selectionRangeProvider.getSelectionRanges(params.getTextDocument.getUri, positions).asJava

  // --- Call Hierarchy ---

  override def prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture[util.List[CallHierarchyItem]] =
    supplyAsync:
      callHierarchyProvider.prepare(
        params.getTextDocument.getUri,
        params.getPosition
      ).asJava

  override def callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    supplyAsync:
      callHierarchyProvider.incomingCalls(params.getItem).asJava

  override def callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    supplyAsync:
      callHierarchyProvider.outgoingCalls(params.getItem).asJava

  // --- Inlay Hints ---

  override def inlayHint(params: InlayHintParams): CompletableFuture[util.List[InlayHint]] =
    supplyAsync:
      inlayHintProvider.getInlayHints(
        params.getTextDocument.getUri,
        params.getRange
      ).asJava

  override def resolveInlayHint(hint: InlayHint): CompletableFuture[InlayHint] =
    supplyAsync:
      inlayHintProvider.resolveInlayHint(hint)

  // --- Completion ---

  override def completion(params: CompletionParams): CompletableFuture[LspEither[util.List[CompletionItem], CompletionList]] =
    supplyAsync:
      val items = completionProvider.getCompletions(
        params.getTextDocument.getUri,
        params.getPosition
      )
      LspEither.forLeft(items.asJava)

  override def resolveCompletionItem(unresolved: CompletionItem): CompletableFuture[CompletionItem] =
    supplyAsync:
      completionProvider.resolveCompletion(unresolved)

  // --- Signature Help ---

  override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
    supplyAsync:
      signatureHelpProvider.getSignatureHelp(
        params.getTextDocument.getUri,
        params.getPosition
      ).orNull

  // --- Code Actions ---

  override def codeAction(params: CodeActionParams): CompletableFuture[util.List[LspEither[Command, CodeAction]]] =
    supplyAsync:
      codeActionProvider.getCodeActions(
        params.getTextDocument.getUri,
        params.getRange,
        params.getContext
      ).map(ca => LspEither.forRight[Command, CodeAction](ca)).asJava

  override def resolveCodeAction(unresolved: CodeAction): CompletableFuture[CodeAction] =
    supplyAsync:
      codeActionProvider.resolveCodeAction(unresolved)

  // --- Rename ---

  override def prepareRename(params: PrepareRenameParams): CompletableFuture[LspEither3[Range, PrepareRenameResult, PrepareRenameDefaultBehavior]] =
    supplyAsync:
      val result = renameProvider.prepareRename(
        params.getTextDocument.getUri,
        params.getPosition
      )
      if result != null then LspEither3.forSecond(result)
      else null

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    supplyAsync:
      renameProvider.rename(
        params.getTextDocument.getUri,
        params.getPosition,
        params.getNewName
      )

  // --- Type Hierarchy ---

  override def prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    supplyAsync:
      typeHierarchyProvider.prepare(
        params.getTextDocument.getUri,
        params.getPosition
      ).asJava

  override def typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    supplyAsync:
      typeHierarchyProvider.supertypes(params.getItem).asJava

  override def typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    supplyAsync:
      typeHierarchyProvider.subtypes(params.getItem).asJava

  // --- Formatting ---

  override def formatting(params: DocumentFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    supplyAsync:
      formattingProvider.getFormatting(params.getTextDocument.getUri).asJava

  override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    supplyAsync:
      formattingProvider.getRangeFormatting(
        params.getTextDocument.getUri,
        params.getRange
      ).asJava

  override def onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    supplyAsync:
      onTypeFormattingProvider.onTypeFormatting(
        params.getTextDocument.getUri,
        params.getPosition,
        params.getCh
      ).asJava

  // --- Document Links ---

  override def documentLink(params: DocumentLinkParams): CompletableFuture[util.List[DocumentLink]] =
    supplyAsync:
      documentLinkProvider.getDocumentLinks(params.getTextDocument.getUri).asJava

  // --- Semantic Tokens ---

  override def semanticTokensFull(params: SemanticTokensParams): CompletableFuture[SemanticTokens] =
    supplyAsync:
      semanticTokensProvider.getSemanticTokensFull(params.getTextDocument.getUri)

  override def semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture[SemanticTokens] =
    supplyAsync:
      semanticTokensProvider.getSemanticTokensRange(
        params.getTextDocument.getUri,
        params.getRange
      )

  // --- Document Highlights ---

  override def documentHighlight(params: DocumentHighlightParams): CompletableFuture[util.List[? <: DocumentHighlight]] =
    supplyAsync:
      documentHighlightProvider.getDocumentHighlights(
        params.getTextDocument.getUri,
        params.getPosition
      ).asJava

  // --- Code Lens ---

  // --- Code Lens ---

  override def codeLens(params: CodeLensParams): CompletableFuture[util.List[? <: CodeLens]] =
    supplyAsync:
      codeLensProvider.getCodeLenses(params.getTextDocument.getUri).asJava

  override def resolveCodeLens(codeLens: CodeLens): CompletableFuture[CodeLens] =
    supplyAsync:
      codeLensProvider.resolveCodeLens(codeLens)
