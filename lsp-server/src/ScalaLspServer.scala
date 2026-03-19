package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import org.jetbrains.scalalsP.intellij.IntellijProjectManager

import java.util.concurrent.CompletableFuture

// Main LSP server implementation using lsp4j.
// Delegates textDocument and workspace requests to specialized services.
class ScalaLspServer(projectPath: String) extends LanguageServer with LanguageClientAware:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val projectManager = IntellijProjectManager()
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

      System.err.println("[ScalaLsp] Server capabilities configured")

      val serverInfo = ServerInfo("intellij-scala-lsp", "0.1.0")
      InitializeResult(capabilities, serverInfo)

  override def initialized(params: InitializedParams): Unit =
    System.err.println("[ScalaLsp] Client confirmed initialization")
    // Wait for indexing to complete
    projectManager.waitForSmartMode()
    // Register daemon listener for push-based diagnostics
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
