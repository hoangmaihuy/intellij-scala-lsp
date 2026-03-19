package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditor, TextEditor}
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

import java.util.Collection as JCollection
import scala.jdk.CollectionConverters.*

// Implements textDocument/publishDiagnostics by extracting IntelliJ's highlighting results.
// Subscribes to DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC to push diagnostics when analysis completes,
// instead of using a crude timer delay.
class DiagnosticsProvider(projectManager: IntellijProjectManager):

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  // Track open files to know which URIs to publish diagnostics for
  private val openFiles = scala.collection.concurrent.TrieMap[String, Boolean]()

  def connect(client: LanguageClient): Unit =
    this.client = client

  def registerDaemonListener(): Unit =
    val project = projectManager.getProject
    project.getMessageBus.connect().subscribe(
      DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
      new DaemonCodeAnalyzer.DaemonListener:
        override def daemonFinished(fileEditors: JCollection[? <: FileEditor]): Unit =
          if client == null then return
          // Publish diagnostics for each file that was just analyzed
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

  def trackClose(uri: String): Unit =
    openFiles.remove(uri)
    // Clear diagnostics for closed file
    if client != null then
      client.publishDiagnostics(new PublishDiagnosticsParams(uri, java.util.Collections.emptyList()))

  def publishDiagnostics(uri: String): Unit =
    if client == null then return

    val diagnostics = collectDiagnostics(uri)
    val params = new PublishDiagnosticsParams(uri, diagnostics.asJava)
    client.publishDiagnostics(params)

  def collectDiagnostics(uri: String): Seq[Diagnostic] =
    ReadAction.compute[Seq[Diagnostic], RuntimeException]: () =>
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
