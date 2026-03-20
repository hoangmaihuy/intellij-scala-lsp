package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import org.jetbrains.scalalsP.intellij.{IntellijProjectManager, SemanticTokensProvider}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

// Main LSP server implementation using lsp4j.
// Delegates textDocument and workspace requests to specialized services.
class ScalaLspServer(
  projectPath: String,
  projectManager: IntellijProjectManager,
  daemonMode: Boolean = false
) extends LanguageServer with LanguageClientAware:

  def this(projectPath: String) = this(projectPath, IntellijProjectManager(), false)
  def this(projectPath: String, pm: IntellijProjectManager) = this(projectPath, pm, false)

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
      if !BootstrapState.bootstrapComplete.await(5, java.util.concurrent.TimeUnit.MINUTES) then
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

      // Signature help with trigger characters
      val signatureHelpOptions = SignatureHelpOptions(java.util.List.of("(", ","))
      capabilities.setSignatureHelpProvider(signatureHelpOptions)

      // Completion with trigger characters
      capabilities.setCompletionProvider(
        CompletionOptions(true, java.util.List.of(".", " "))
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
      codeActionOptions.setResolveProvider(true)
      capabilities.setCodeActionProvider(codeActionOptions)

      // Semantic tokens
      val semanticTokensOptions = SemanticTokensWithRegistrationOptions(SemanticTokensProvider.legend)
      semanticTokensOptions.setFull(true)
      semanticTokensOptions.setRange(true)
      capabilities.setSemanticTokensProvider(semanticTokensOptions)

      // Document links
      capabilities.setDocumentLinkProvider(DocumentLinkOptions())

      // Formatting
      capabilities.setDocumentFormattingProvider(true)
      capabilities.setDocumentRangeFormattingProvider(true)

      // Workspace folders
      val workspaceFolderOptions = WorkspaceFoldersOptions()
      workspaceFolderOptions.setSupported(true)
      workspaceFolderOptions.setChangeNotifications(true)
      val workspaceCapabilities = WorkspaceServerCapabilities(workspaceFolderOptions)
      capabilities.setWorkspace(workspaceCapabilities)

      // Open initial workspace folders (beyond the root)
      val workspaceFolders = Option(params.getWorkspaceFolders)
      workspaceFolders.foreach: folders =>
        folders.asScala.foreach: folder =>
          val uri = folder.getUri
          val folderPath = if uri.startsWith("file://") then java.net.URI.create(uri).getPath else uri
          if folderPath != effectivePath then
            System.err.println(s"[ScalaLsp] Opening additional workspace folder: $folderPath")
            try projectManager.openProject(folderPath)
            catch case e: Exception =>
              System.err.println(s"[ScalaLsp] Failed to open workspace folder: ${e.getMessage}")

      System.err.println("[ScalaLsp] Server capabilities configured")

      val serverInfo = ServerInfo("intellij-scala-lsp", "0.1.0")
      InitializeResult(capabilities, serverInfo)

  override def initialized(params: InitializedParams): Unit =
    System.err.println("[ScalaLsp] Client confirmed initialization")
    // Run indexing wait and daemon registration in background
    // to avoid blocking the lsp4j message processing thread
    java.util.concurrent.CompletableFuture.runAsync: () =>
      if client != null then
        reportIndexingProgress()
      else
        projectManager.waitForSmartMode()
      textDocumentService.registerDaemonListener()
      System.err.println("[ScalaLsp] Project indexing complete, ready for requests")
      if client != null then
        client.logMessage(MessageParams(MessageType.Info, "Indexing complete, ready for requests"))

  private def reportIndexingProgress(): Unit =
    import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither}
    val token = "indexing"
    val createParams = WorkDoneProgressCreateParams()
    createParams.setToken(token)
    try client.createProgress(createParams).get()
    catch case _: Exception => ()

    val begin = WorkDoneProgressBegin()
    begin.setTitle("Indexing")
    begin.setMessage("Scanning project files...")
    begin.setCancellable(false)
    client.notifyProgress(ProgressParams(LspEither.forLeft(token), LspEither.forLeft(begin)))

    val project = projectManager.getProject
    val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
    val startTime = System.currentTimeMillis()
    while dumbService.isDumb do
      val elapsed = (System.currentTimeMillis() - startTime) / 1000
      val report = WorkDoneProgressReport()
      report.setMessage(s"Indexing... (${elapsed}s)")
      client.notifyProgress(ProgressParams(LspEither.forLeft(token), LspEither.forLeft(report)))
      try Thread.sleep(1000)
      catch case _: InterruptedException => ()

    val end = WorkDoneProgressEnd()
    end.setMessage("Indexing complete")
    client.notifyProgress(ProgressParams(LspEither.forLeft(token), LspEither.forLeft(end)))

  override def shutdown(): CompletableFuture[AnyRef] =
    CompletableFuture.supplyAsync: () =>
      System.err.println("[ScalaLsp] Shutting down...")
      if !daemonMode then projectManager.closeProject()
      null

  override def exit(): Unit =
    System.err.println("[ScalaLsp] Exiting")
    if !daemonMode then System.exit(0)

  override def getTextDocumentService: TextDocumentService = textDocumentService

  override def getWorkspaceService: WorkspaceService = workspaceService

