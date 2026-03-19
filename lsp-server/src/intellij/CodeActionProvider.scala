package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.codeInsight.intention.{IntentionAction, IntentionManager}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

// Implements textDocument/codeAction by collecting:
// 1. Quick fixes from IntelliJ's highlighting/inspection results
// 2. Intention actions from IntentionManager
class CodeActionProvider(projectManager: IntellijProjectManager):

  def getCodeActions(
    uri: String,
    range: Range,
    context: CodeActionContext
  ): Seq[CodeAction] =
    ReadAction.compute[Seq[CodeAction], RuntimeException]: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val project = projectManager.getProject
        val startOffset = PsiUtils.positionToOffset(document, range.getStart)
        val endOffset = PsiUtils.positionToOffset(document, range.getEnd)

        val actions = Seq.newBuilder[CodeAction]

        // 1. Quick fixes from highlighting
        actions ++= collectQuickFixes(document, project, startOffset, endOffset)

        // 2. Intention actions
        actions ++= collectIntentionActions(psiFile, document, project, startOffset)

        actions.result()

      result.getOrElse(Seq.empty)

  // --- Quick Fixes ---

  private def collectQuickFixes(
    document: com.intellij.openapi.editor.Document,
    project: com.intellij.openapi.project.Project,
    startOffset: Int,
    endOffset: Int
  ): Seq[CodeAction] =
    try
      val highlights = DaemonCodeAnalyzerImpl.getHighlights(
        document, HighlightSeverity.INFORMATION, project
      )
      if highlights == null then return Seq.empty

      highlights.asScala
        .filter: h =>
          h.startOffset <= endOffset && h.endOffset >= startOffset &&
          h.getDescription != null && h.getDescription.nonEmpty
        .flatMap: h =>
          extractQuickFixes(h, document)
        .toSeq
    catch
      case _: Exception => Seq.empty

  private def extractQuickFixes(
    info: HighlightInfo,
    document: com.intellij.openapi.editor.Document
  ): Seq[CodeAction] =
    try
      val quickFixes = info.quickFixActionRanges
      if quickFixes == null then return Seq.empty

      quickFixes.asScala.flatMap: pair =>
        try
          val fixAction = pair.getFirst.getAction
          if fixAction != null then
            val action = CodeAction()
            action.setTitle(fixAction.getText)
            action.setKind(CodeActionKind.QuickFix)

            // Link to source diagnostic
            val diagnostic = Diagnostic()
            diagnostic.setRange(Range(
              PsiUtils.offsetToPosition(document, info.startOffset),
              PsiUtils.offsetToPosition(document, info.endOffset)
            ))
            diagnostic.setMessage(info.getDescription)
            diagnostic.setSeverity(toLspSeverity(info.getSeverity))
            diagnostic.setSource("intellij-scala")
            action.setDiagnostics(java.util.List.of(diagnostic))

            Some(action)
          else None
        catch
          case _: Exception => None
      .toSeq
    catch
      case _: Exception => Seq.empty

  // --- Intention Actions ---

  private def collectIntentionActions(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    project: com.intellij.openapi.project.Project,
    offset: Int
  ): Seq[CodeAction] =
    var editor: Editor = null
    try
      editor = EditorFactory.getInstance().createEditor(document, project)
      editor.getCaretModel.moveToOffset(offset)

      val intentionManager = IntentionManager.getInstance
      if intentionManager == null then return Seq.empty

      val availableIntentions = intentionManager.getAvailableIntentions
      if availableIntentions == null then return Seq.empty

      availableIntentions.asScala.flatMap: intention =>
        try
          if intention.isAvailable(project, editor, psiFile) then
            val action = CodeAction()
            action.setTitle(intention.getText)
            action.setKind(categorizeIntention(intention))
            Some(action)
          else None
        catch
          case _: Exception => None
      .toSeq
    catch
      case _: Exception => Seq.empty
    finally
      if editor != null then
        EditorFactory.getInstance().releaseEditor(editor)

  private def categorizeIntention(intention: IntentionAction): String =
    val text = intention.getText.toLowerCase
    if text.contains("extract") then CodeActionKind.RefactorExtract
    else if text.contains("inline") then CodeActionKind.RefactorInline
    else if text.contains("import") then CodeActionKind.Source
    else if text.contains("organize") then CodeActionKind.SourceOrganizeImports
    else CodeActionKind.Refactor

  private[intellij] def toLspSeverity(severity: HighlightSeverity): DiagnosticSeverity =
    if severity.compareTo(HighlightSeverity.ERROR) >= 0 then DiagnosticSeverity.Error
    else if severity.compareTo(HighlightSeverity.WARNING) >= 0 then DiagnosticSeverity.Warning
    else if severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0 then DiagnosticSeverity.Information
    else DiagnosticSeverity.Hint
