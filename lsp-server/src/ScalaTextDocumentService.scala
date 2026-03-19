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

  def connect(client: LanguageClient): Unit =
    this.client = client
    diagnosticsProvider.connect(client)

  // --- Document Synchronization ---

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    documentSync.didOpen(uri, params.getTextDocument.getText)
    scheduleDiagnostics(uri)

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    val changes = params.getContentChanges.asScala.toSeq
    // We use Full sync, so there's always one change with the full text
    changes.headOption.foreach: change =>
      documentSync.didChange(uri, change.getText)
    scheduleDiagnostics(uri)

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    documentSync.didClose(params.getTextDocument.getUri)

  override def didSave(params: DidSaveTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    documentSync.didSave(uri)
    scheduleDiagnostics(uri)

  private def scheduleDiagnostics(uri: String): Unit =
    // Publish diagnostics asynchronously after a short delay to let IntelliJ analyze
    CompletableFuture.runAsync: () =>
      try
        Thread.sleep(500) // Allow IntelliJ's daemon analyzer to process changes
        diagnosticsProvider.publishDiagnostics(uri)
      catch
        case _: InterruptedException => ()
        case e: Exception =>
          System.err.println(s"[DiagnosticsProvider] Error publishing diagnostics: ${e.getMessage}")

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
