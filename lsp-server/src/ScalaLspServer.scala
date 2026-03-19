package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import org.jetbrains.scalalsP.intellij.IntellijProjectManager

import java.util.concurrent.CompletableFuture

// Main LSP server implementation using lsp4j.
// Delegates textDocument and workspace requests to specialized services.
class ScalaLspServer(projectPath: String, projectManager: IntellijProjectManager) extends LanguageServer with LanguageClientAware:

  def this(projectPath: String) = this(projectPath, IntellijProjectManager())

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val textDocumentService = ScalaTextDocumentService(projectManager)
  private val workspaceService = ScalaWorkspaceService(projectManager)

  def connect(client: LanguageClient): Unit =
    this.client = client
    textDocumentService.connect(client)
    workspaceService.connect(client)

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] =
    CompletableFuture.supplyAsync: () =>
      val rootUri = Option(params.getRootUri).orElse(Option(params.getRootPath))
      val effectivePath = rootUri match
        case Some(uri) if uri.startsWith("file://") =>
          java.net.URI.create(uri).getPath
        case Some(path) => path
        case None => projectPath

      System.err.println(s"[ScalaLsp] Initializing with project: $effectivePath")

      // Wait for IntelliJ platform bootstrap (started by ScalaLspMain in background)
      // In test mode, the latch is pre-counted down by ScalaLspTestBase.setUp()
      // Timeout after 5 minutes to avoid hanging forever if bootstrap fails
      if !ScalaLspMain.bootstrapComplete.await(5, java.util.concurrent.TimeUnit.MINUTES) then
        throw RuntimeException("IntelliJ platform bootstrap timed out")

      // Open the project in headless IntelliJ
      projectManager.openProject(effectivePath)

      val capabilities = ServerCapabilities()

      // Text document sync - full sync mode
      capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)

      // Navigation capabilities
      capabilities.setDefinitionProvider(true)
      capabilities.setTypeDefinitionProvider(true)
      capabilities.setImplementationProvider(true)
      capabilities.setReferencesProvider(true)
      capabilities.setHoverProvider(true)
      capabilities.setDocumentSymbolProvider(true)
      capabilities.setWorkspaceSymbolProvider(true)
      capabilities.setFoldingRangeProvider(true)
      capabilities.setSelectionRangeProvider(true)
      capabilities.setCallHierarchyProvider(true)

      // Inlay hints
      capabilities.setInlayHintProvider(true)

      // Completion with trigger characters
      capabilities.setCompletionProvider(
        CompletionOptions(false, java.util.List.of(".", " "))
      )

      // Rename
      val renameOptions = RenameOptions()
      renameOptions.setPrepareProvider(true)
      capabilities.setRenameProvider(renameOptions)

      // Type hierarchy
      capabilities.setTypeHierarchyProvider(true)

      // Execute commands
      val executeCommandOptions = ExecuteCommandOptions(
        java.util.List.of("scala.organizeImports", "scala.reformat")
      )
      capabilities.setExecuteCommandProvider(executeCommandOptions)

      // Code actions
      val codeActionOptions = CodeActionOptions(
        java.util.List.of(
          CodeActionKind.QuickFix,
          CodeActionKind.Refactor,
          CodeActionKind.Source,
          CodeActionKind.RefactorExtract,
          CodeActionKind.RefactorInline
        )
      )
      capabilities.setCodeActionProvider(codeActionOptions)

      System.err.println("[ScalaLsp] Server capabilities configured")

      val serverInfo = ServerInfo("intellij-scala-lsp", "0.1.0")
      InitializeResult(capabilities, serverInfo)

  override def initialized(params: InitializedParams): Unit =
    System.err.println("[ScalaLsp] Client confirmed initialization")
    // Run indexing wait and daemon registration in background
    // to avoid blocking the lsp4j message processing thread
    java.util.concurrent.CompletableFuture.runAsync: () =>
      projectManager.waitForSmartMode()
      textDocumentService.registerDaemonListener()
      System.err.println("[ScalaLsp] Project indexing complete, ready for requests")

  override def shutdown(): CompletableFuture[AnyRef] =
    CompletableFuture.supplyAsync: () =>
      System.err.println("[ScalaLsp] Shutting down...")
      projectManager.closeProject()
      null

  override def exit(): Unit =
    System.err.println("[ScalaLsp] Exiting")
    System.exit(0)

  override def getTextDocumentService: TextDocumentService = textDocumentService

  override def getWorkspaceService: WorkspaceService = workspaceService

