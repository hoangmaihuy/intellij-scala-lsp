package org.jetbrains.scalalsP

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.{LanguageClient, WorkspaceService}
import org.jetbrains.scalalsP.intellij.{DiagnosticsProvider, IntellijProjectManager, PsiUtils, ScalaTypes, SymbolProvider}

import java.util
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import scala.jdk.CollectionConverters.*

// Handles workspace LSP requests.
class ScalaWorkspaceService(projectManager: IntellijProjectManager, diagnosticsProvider: DiagnosticsProvider, textDocumentService: ScalaTextDocumentService) extends WorkspaceService:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val symbolProvider = SymbolProvider(projectManager)

  // Dedicated thread pool — same rationale as ScalaTextDocumentService.lspExecutor
  private val lspExecutor: ExecutorService = Executors.newCachedThreadPool: r =>
    val t = Thread(r, "lsp-workspace-handler")
    t.setDaemon(true)
    t

  private def supplyAsync[T](f: => T): CompletableFuture[T] =
    CompletableFuture.supplyAsync((() => f): java.util.function.Supplier[T], lspExecutor)

  private def cancellableAsync[T](f: java.util.concurrent.atomic.AtomicBoolean => T): CompletableFuture[T] =
    CancellableAsync(lspExecutor)(f)

  private val requestCounter = new java.util.concurrent.atomic.AtomicLong(0)

  private def logged[T](method: String, params: => String)(f: => CompletableFuture[T]): CompletableFuture[T] =
    val id = requestCounter.incrementAndGet()
    val start = System.currentTimeMillis()
    System.err.println(s"[LSP] --> $method #$id $params")
    val future = f
    // Use the original future as a side-effect callback target, but return the original.
    // Returning whenComplete's derived future would break cancellation propagation:
    // lsp4j cancels the returned future, but whenComplete returns a NEW future,
    // so cancel() wouldn't reach the original (e.g. from CancellableAsync).
    future.whenComplete: (_, error) =>
      val elapsed = System.currentTimeMillis() - start
      if error != null then
        val msg = Option(error.getCause).map(_.getMessage).getOrElse(error.getMessage)
        System.err.println(s"[LSP] <-- $method #$id ERROR ${elapsed}ms: $msg")
      else
        System.err.println(s"[LSP] <-- $method #$id ${elapsed}ms")
    future

  private def shortUri(uri: String): String =
    val idx = uri.lastIndexOf('/')
    if idx >= 0 then uri.substring(idx + 1) else uri

  def connect(client: LanguageClient): Unit =
    this.client = client

  override def symbol(params: WorkspaceSymbolParams): CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[util.List[? <: SymbolInformation], util.List[? <: WorkspaceSymbol]]] =
    logged("workspace/symbol", s"query=${params.getQuery}"):
      cancellableAsync: cancelled =>
        val symbols = symbolProvider.workspaceSymbols(params.getQuery, cancelled)
        org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(symbols.asJava)

  override def executeCommand(params: ExecuteCommandParams): CompletableFuture[AnyRef] =
    val command = params.getCommand
    System.err.println(s"[LSP] --> workspace/executeCommand $command")
    val start = System.currentTimeMillis()
    def logDone(): Unit = System.err.println(s"[LSP] <-- workspace/executeCommand $command ${System.currentTimeMillis() - start}ms")
    val args = params.getArguments
    val result: AnyRef = command match
      case "scala.organizeImports" =>
        executeOnFile(args): psiFile =>
          val processor = OptimizeImportsProcessor(projectManager.getProject, psiFile)
          processor.run()
      case "scala.reformat" =>
        executeOnFile(args): psiFile =>
          CodeStyleManager.getInstance(projectManager.getProject).reformat(psiFile)
      case "scala.pullDiagnostics" =>
        if args != null && !args.isEmpty then
          val uri = args.get(0) match
            case s: String => s
            case gson: com.google.gson.JsonPrimitive => gson.getAsString
            case other => other.toString.replaceAll("\"", "")
          val future: CompletableFuture[AnyRef] = supplyAsync(diagnosticsProvider.runAnalysisAndCollect(uri).asJava)
          future.whenComplete((_, _) => logDone())
          return future
        null
      case "scala.gotoLocation" =>
        if client != null && args != null && args.size() >= 3 then
          val targetUri = args.get(0).toString.replaceAll("\"", "")
          val targetLine = args.get(1).toString.replaceAll("\"", "").toInt
          val targetChar = args.get(2).toString.replaceAll("\"", "").toInt
          val showParams = ShowDocumentParams(targetUri)
          showParams.setSelection(Range(Position(targetLine, targetChar), Position(targetLine, targetChar)))
          showParams.setTakeFocus(true)
          try client.showDocument(showParams).get(10, java.util.concurrent.TimeUnit.SECONDS)
          catch case _: Exception => ()
        null
      case "scala.referencesWithTypes" =>
        import com.google.gson.{JsonArray, JsonObject}
        val arr = new JsonArray()
        for r <- textDocumentService.getLastReferencesWithTypes do
          val obj = new JsonObject()
          val locObj = new JsonObject()
          locObj.addProperty("uri", r.location.getUri)
          val rangeObj = new JsonObject()
          val startObj = new JsonObject()
          startObj.addProperty("line", r.location.getRange.getStart.getLine)
          startObj.addProperty("character", r.location.getRange.getStart.getCharacter)
          val endObj = new JsonObject()
          endObj.addProperty("line", r.location.getRange.getEnd.getLine)
          endObj.addProperty("character", r.location.getRange.getEnd.getCharacter)
          rangeObj.add("start", startObj)
          rangeObj.add("end", endObj)
          locObj.add("range", rangeObj)
          obj.add("location", locObj)
          obj.addProperty("usageType", r.usageType)
          arr.add(obj)
        logDone()
        return CompletableFuture.completedFuture(arr)
      case _ =>
        System.err.println(s"[WorkspaceService] Unknown command: $command")
        null
    logDone()
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
    val added = Option(event.getAdded).map(_.asScala.size).getOrElse(0)
    val removed = Option(event.getRemoved).map(_.asScala.size).getOrElse(0)
    System.err.println(s"[LSP] notify workspace/didChangeWorkspaceFolders +$added -$removed")
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

  override def willRenameFiles(params: RenameFilesParams): CompletableFuture[WorkspaceEdit] =
    val fileNames = params.getFiles.asScala.map(f => shortUri(f.getOldUri) + " -> " + shortUri(f.getNewUri)).mkString(", ")
    logged("workspace/willRenameFiles", fileNames):
      supplyAsync:
        try
          val allDocChanges = new java.util.ArrayList[org.eclipse.lsp4j.jsonrpc.messages.Either[TextDocumentEdit, ResourceOperation]]()

          params.getFiles.asScala.foreach: fileRename =>
            val oldUri = fileRename.getOldUri
            val newUri = fileRename.getNewUri

            // Only process Scala file renames
            if oldUri.endsWith(".scala") then
              val oldFileName = oldUri.substring(oldUri.lastIndexOf('/') + 1).stripSuffix(".scala")
              val newFileName = newUri.substring(newUri.lastIndexOf('/') + 1).stripSuffix(".scala")

              // Find top-level type definitions matching old filename and generate rename edits
              val edits = projectManager.smartReadAction: () =>
                (for
                  psiFile <- projectManager.findPsiFile(oldUri)
                  vf <- projectManager.findVirtualFile(oldUri)
                  document <- Option(FileDocumentManager.getInstance().getDocument(vf))
                yield
                  import com.intellij.psi.PsiNameIdentifierOwner
                  psiFile.getChildren.collect:
                    case elem: PsiNameIdentifierOwner if ScalaTypes.isTypeDefinition(elem) && elem.getName == oldFileName => elem
                  .flatMap: td =>
                    Option(td.getNameIdentifier).map: nameId =>
                      val start = PsiUtils.offsetToPosition(document, nameId.getTextRange.getStartOffset)
                      val end = PsiUtils.offsetToPosition(document, nameId.getTextRange.getEndOffset)
                      TextEdit(Range(start, end), newFileName)
                  .toSeq
                ).getOrElse(Seq.empty)

              if edits.nonEmpty then
                val versionedId = VersionedTextDocumentIdentifier(oldUri, null)
                val docEdit = TextDocumentEdit(versionedId, edits.asJava)
                allDocChanges.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(docEdit))

          if allDocChanges.isEmpty then WorkspaceEdit()
          else WorkspaceEdit(allDocChanges)
        catch
          case e: Exception =>
            System.err.println(s"[WorkspaceService] willRenameFiles error: ${e.getMessage}")
            WorkspaceEdit()

  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit =
    System.err.println(s"[WorkspaceService] Configuration changed: ${params.getSettings}")

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit =
    if params.getChanges == null || params.getChanges.isEmpty then return
    // Collect changed virtual files and trigger async VFS refresh so IntelliJ picks up external changes
    val changedFiles = params.getChanges.asScala.flatMap: change =>
      val uri = change.getUri
      projectManager.findVirtualFile(uri)
    .toArray

    if changedFiles.nonEmpty then
      com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(
        /* async= */ true,
        /* recursive= */ false,
        /* reloadChildren= */ false,
        changedFiles*
      )
