package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, DaemonProgressIndicator, HighlightInfo, HighlightingSession, HighlightingSessionImpl}
import com.intellij.codeInsight.multiverse.CodeInsightContexts
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditor, TextEditor}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Computable, ProperTextRange}
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

import java.util.Collection as JCollection
import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.jdk.CollectionConverters.*

class DiagnosticsProvider(projectManager: IntellijProjectManager):

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val openFiles = scala.collection.concurrent.TrieMap[String, Boolean]()

  // Debounced analysis scheduler: IntelliJ's daemon code analyzer only runs on files
  // open in IntelliJ's own editor (FileEditor). LSP-opened files have no FileEditor,
  // so we must explicitly run analysis passes and publish diagnostics.
  private val analysisExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor: r =>
    val t = new Thread(r, "lsp-diagnostics-analyzer")
    t.setDaemon(true)
    t
  private val pendingAnalysis = scala.collection.concurrent.TrieMap[String, ScheduledFuture[?]]()

  def connect(client: LanguageClient): Unit =
    this.client = client

  def registerDaemonListener(): Unit =
    val project = projectManager.getProject
    project.getMessageBus.connect().subscribe(
      DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
      new DaemonCodeAnalyzer.DaemonListener:
        override def daemonFinished(fileEditors: JCollection[? <: FileEditor]): Unit =
          if client == null then return
          fileEditors.asScala.foreach:
            case textEditor: TextEditor =>
              val vf = textEditor.getFile
              if vf != null then
                val uri = PsiUtils.vfToUri(vf)
                if openFiles.contains(uri) then
                  publishDiagnostics(uri)
            case _ => ()
    )

  def trackOpen(uri: String): Unit =
    openFiles.put(uri, true)
    // Don't auto-analyze on open — wait for didChange/didSave to avoid
    // expensive runMainPasses on files opened transiently (e.g., by go-to-definition)

  def trackClose(uri: String): Unit =
    openFiles.remove(uri)
    cancelPendingAnalysis(uri)
    if client != null then
      client.publishDiagnostics(new PublishDiagnosticsParams(uri, java.util.Collections.emptyList()))

  /** Schedule debounced analysis for a file. Subsequent calls within the delay window
    * cancel the previous scheduled run, ensuring rapid edits don't overwhelm the analyzer. */
  def scheduleAnalysis(uri: String, delayMs: Long = 1000): Unit =
    if client == null || !openFiles.contains(uri) then return
    if !isProjectFile(uri) then return
    cancelPendingAnalysis(uri)
    val future = analysisExecutor.schedule(
      (() => runAnalysisAndPublish(uri)): Runnable,
      delayMs,
      TimeUnit.MILLISECONDS
    )
    pendingAnalysis.put(uri, future)

  private def cancelPendingAnalysis(uri: String): Unit =
    pendingAnalysis.remove(uri).foreach(_.cancel(false))

  /** Check if a URI belongs to a project workspace (not a JAR source or external file). */
  private def isProjectFile(uri: String): Boolean =
    if !uri.startsWith("file://") then return false
    val path = java.net.URI.create(uri).getPath
    projectManager.getAllProjects.exists: project =>
      val basePath = project.getBasePath
      basePath != null && path.startsWith(basePath)

  /** Run analysis passes and publish results to the client. */
  private def runAnalysisAndPublish(uri: String): Unit =
    if client == null || !openFiles.contains(uri) then return
    try
      val diagnostics = runAnalysisAndCollect(uri)
      val params = new PublishDiagnosticsParams(uri, diagnostics.asJava)
      client.publishDiagnostics(params)
    catch
      case e: Exception =>
        System.err.println(s"[DiagnosticsProvider] Failed to analyze and publish for $uri: ${e.getMessage}")

  def publishDiagnostics(uri: String): Unit =
    if client == null then return
    val diagnostics = collectDiagnostics(uri)
    val params = new PublishDiagnosticsParams(uri, diagnostics.asJava)
    client.publishDiagnostics(params)

  /** Run analysis passes directly and collect diagnostics on demand.
    * Uses DaemonCodeAnalyzerImpl.runMainPasses() — same approach as
    * intellij-community's MCP server (AnalysisToolset.get_file_problems).
    * Must NOT run on EDT — runMainPasses requires a background thread. */
  def runAnalysisAndCollect(uri: String): Seq[Diagnostic] =
    import com.intellij.openapi.application.ReadAction
    // Resolve file references under read action
    val resolved = ReadAction.compute[Option[(com.intellij.psi.PsiFile, com.intellij.openapi.editor.Document)], RuntimeException]: () =>
      for
        vf <- projectManager.findVirtualFile(uri)
        psiFile <- projectManager.findPsiFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield (psiFile, document)

    resolved match
      case None =>
        System.err.println(s"[DiagnosticsProvider] Could not resolve file for $uri")
        Seq.empty
      case Some((psiFile, document)) =>
        System.err.println(s"[DiagnosticsProvider] Analyzing $uri (${document.getTextLength} chars)")
        val project = projectManager.getProject
        val daemonIndicator = new DaemonProgressIndicator()
        val range = new ProperTextRange(0, document.getTextLength)
        val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project).asInstanceOf[DaemonCodeAnalyzerImpl]

        try
          // Container for results from inside the callback
          val results = new java.util.ArrayList[Diagnostic]()
          ProgressManager.getInstance().runProcess(
            (() =>
              HighlightingSessionImpl.runInsideHighlightingSession(
                psiFile,
                CodeInsightContexts.defaultContext(),
                null, // editorColorsScheme
                range,
                false, // canChangeDocument
                // runMainPasses MUST be called inside this callback while the session is active
                new java.util.function.Consumer[HighlightingSession]:
                  override def accept(session: HighlightingSession): Unit =
                    session.asInstanceOf[HighlightingSessionImpl].setMinimumSeverity(HighlightSeverity.WARNING)
                    val highlightInfos = codeAnalyzer.runMainPasses(psiFile, document, daemonIndicator)
                    highlightInfos.asScala
                      .filter(h => h.getDescription != null && h.getDescription.nonEmpty)
                      .filter(h => h.getSeverity.compareTo(HighlightSeverity.WARNING) >= 0)
                      .foreach(h => results.add(toDiagnostic(document, h)))
              )
            ): Runnable,
            daemonIndicator
          )
          System.err.println(s"[DiagnosticsProvider] runMainPasses found ${results.size()} diagnostics")
          results.asScala.toSeq
        catch
          case e: Exception =>
            System.err.println(s"[DiagnosticsProvider] runMainPasses failed: ${e.getMessage}")
            Seq.empty

  def collectDiagnostics(uri: String): Seq[Diagnostic] =
    projectManager.smartReadAction: () =>
      val result = for
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val project = projectManager.getProject
        val highlights = DaemonCodeAnalyzerImpl.getHighlights(
          document,
          HighlightSeverity.WARNING,
          project
        )

        if highlights == null then Seq.empty
        else
          highlights.asScala
            .filter(h => h.getDescription != null && h.getDescription.nonEmpty)
            .map(h => toDiagnostic(document, h))
            .toSeq

      result.getOrElse(Seq.empty)

  private def toDiagnostic(document: com.intellij.openapi.editor.Document, info: HighlightInfo): Diagnostic =
    val start = PsiUtils.offsetToPosition(document, info.startOffset)
    val end = PsiUtils.offsetToPosition(document, info.endOffset)
    val range = new Range(start, end)

    val diagnostic = new Diagnostic(range, info.getDescription)
    diagnostic.setSeverity(toLspSeverity(info.getSeverity))
    diagnostic.setSource("intellij-scala")
    diagnostic

  private[intellij] def toLspSeverity(severity: HighlightSeverity): DiagnosticSeverity =
    if severity.compareTo(HighlightSeverity.ERROR) >= 0 then
      DiagnosticSeverity.Error
    else if severity.compareTo(HighlightSeverity.WARNING) >= 0 then
      DiagnosticSeverity.Warning
    else if severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0 then
      DiagnosticSeverity.Information
    else
      DiagnosticSeverity.Hint
