package org.jetbrains.scalalsP

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.{LanguageClient, WorkspaceService}
import org.jetbrains.scalalsP.intellij.{IntellijProjectManager, PsiUtils, SymbolProvider}

import java.util
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

// Handles workspace LSP requests.
class ScalaWorkspaceService(projectManager: IntellijProjectManager) extends WorkspaceService:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val symbolProvider = SymbolProvider(projectManager)

  def connect(client: LanguageClient): Unit =
    this.client = client

  override def symbol(params: WorkspaceSymbolParams): CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[util.List[? <: SymbolInformation], util.List[? <: WorkspaceSymbol]]] =
    CompletableFuture.supplyAsync: () =>
      val symbols = symbolProvider.workspaceSymbols(params.getQuery)
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(symbols.asJava)

  override def executeCommand(params: ExecuteCommandParams): CompletableFuture[AnyRef] =
    val command = params.getCommand
    val args = params.getArguments
    val result: AnyRef = command match
      case "scala.organizeImports" =>
        executeOnFile(args): psiFile =>
          val processor = OptimizeImportsProcessor(projectManager.getProject, psiFile)
          processor.run()
      case "scala.reformat" =>
        executeOnFile(args): psiFile =>
          CodeStyleManager.getInstance(projectManager.getProject).reformat(psiFile)
      case _ =>
        System.err.println(s"[WorkspaceService] Unknown command: $command")
        null
    CompletableFuture.completedFuture(result)

  private def executeOnFile(args: java.util.List[AnyRef])(action: com.intellij.psi.PsiFile => Unit): Null =
    if args == null || args.isEmpty then
      System.err.println("[WorkspaceService] executeCommand: missing file URI argument")
      return null

    val uri = args.get(0) match
      case s: String => s
      case gson: com.google.gson.JsonPrimitive => gson.getAsString
      case other =>
        System.err.println(s"[WorkspaceService] executeCommand: unexpected argument type: ${other.getClass}")
        return null

    projectManager.findPsiFile(uri) match
      case Some(psiFile) =>
        val vfOpt = projectManager.findVirtualFile(uri)

        // Helper to get document text (needs read access)
        def getDocText(): Option[String] =
          ReadAction.compute[Option[String], RuntimeException]: () =>
            vfOpt.flatMap(vf => Option(FileDocumentManager.getInstance().getDocument(vf))).map(_.getText)

        // Capture document text before mutation
        val beforeText = getDocText()

        // Run write action - if on EDT already, run directly; otherwise use invokeAndWait
        val runWriteAction: Runnable = () =>
          WriteCommandAction.runWriteCommandAction(projectManager.getProject, (() =>
            action(psiFile)
          ): Runnable)

        if ApplicationManager.getApplication.isDispatchThread then
          runWriteAction.run()
        else
          ApplicationManager.getApplication.invokeAndWait(runWriteAction)

        // Notify client of changes via workspace/applyEdit so it stays in sync
        if client != null then
          val afterText = getDocText()
          (beforeText, afterText) match
            case (Some(before), Some(after)) if before != after =>
              // Send full document replacement to client
              val fullRange = new Range(
                new Position(0, 0),
                new Position(before.count(_ == '\n') + 1, 0)
              )
              val edit = WorkspaceEdit(java.util.Map.of(uri, java.util.List.of(TextEdit(fullRange, after))))
              client.applyEdit(ApplyWorkspaceEditParams(edit))
            case _ => () // No changes
      case None =>
        System.err.println(s"[WorkspaceService] File not found: $uri")

    null

  override def didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams): Unit =
    val event = params.getEvent
    if event == null then return
    if event.getAdded != null then
      event.getAdded.asScala.foreach: folder =>
        val uri = folder.getUri
        val path = if uri.startsWith("file://") then java.net.URI.create(uri).getPath else uri
        System.err.println(s"[WorkspaceService] Adding workspace folder: $path")
        try projectManager.openProject(path)
        catch case e: Exception =>
          System.err.println(s"[WorkspaceService] Failed to open folder: ${e.getMessage}")
    if event.getRemoved != null then
      event.getRemoved.asScala.foreach: folder =>
        val uri = folder.getUri
        val path = if uri.startsWith("file://") then java.net.URI.create(uri).getPath else uri
        System.err.println(s"[WorkspaceService] Removing workspace folder: $path")
        projectManager.closeProject(path)

  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()
