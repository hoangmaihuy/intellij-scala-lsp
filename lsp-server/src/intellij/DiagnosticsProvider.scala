package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

import scala.jdk.CollectionConverters.*

// Implements textDocument/publishDiagnostics by extracting IntelliJ's highlighting results.
// Runs after document changes and pushes diagnostics to the client.
class DiagnosticsProvider(projectManager: IntellijProjectManager):

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized

  def connect(client: LanguageClient): Unit =
    this.client = client

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

        // Get all highlights with severity >= WARNING from IntelliJ's analyzer
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

  private def toLspSeverity(severity: HighlightSeverity): DiagnosticSeverity =
    if severity.compareTo(HighlightSeverity.ERROR) >= 0 then
      DiagnosticSeverity.Error
    else if severity.compareTo(HighlightSeverity.WARNING) >= 0 then
      DiagnosticSeverity.Warning
    else if severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0 then
      DiagnosticSeverity.Information
    else
      DiagnosticSeverity.Hint
