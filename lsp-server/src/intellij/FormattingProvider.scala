package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.*

// Uses format-on-copy approach (PsiFileFactory.createFileFromText) instead of format-then-undo
// because undo is unreliable in headless IntelliJ mode. Returns full-file replacement edit.
class FormattingProvider(projectManager: IntellijProjectManager):

  def getFormatting(uri: String): Seq[TextEdit] =
    try
      // Collect data inside read action, then format OUTSIDE to avoid
      // deadlock: smartReadAction holds read lock, formatOnCopy needs write lock via invokeAndWait
      val dataOpt = projectManager.smartReadAction: () =>
        for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          (document.getText, psiFile.getLanguage)

      dataOpt match
        case Some((originalText, language)) =>
          val formattedText = formatOnCopy(originalText, language)
          if formattedText != originalText then
            computeFullReplacement(originalText, formattedText)
          else
            Seq.empty
        case None => Seq.empty
    catch
      case e: Exception =>
        System.err.println(s"[FormattingProvider] Error: ${e.getMessage}")
        Seq.empty

  def getRangeFormatting(uri: String, range: Range): Seq[TextEdit] =
    try
      // Collect data inside read action, then format OUTSIDE to avoid deadlock
      val dataOpt = projectManager.smartReadAction: () =>
        for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val originalText = document.getText
          val startOffset = PsiUtils.positionToOffset(document, range.getStart)
          val endOffset = PsiUtils.positionToOffset(document, range.getEnd)
          (originalText, psiFile.getLanguage, startOffset, endOffset)

      dataOpt match
        case Some((originalText, language, startOffset, endOffset)) =>
          val formattedText = formatRangeOnCopy(originalText, language, startOffset, endOffset)
          if formattedText != originalText then
            computeFullReplacement(originalText, formattedText)
          else
            Seq.empty
        case None => Seq.empty
    catch
      case e: Exception =>
        System.err.println(s"[FormattingProvider] Error in range formatting: ${e.getMessage}")
        Seq.empty

  private def formatOnCopy(
    originalText: String,
    language: com.intellij.lang.Language
  ): String =
    val project = projectManager.getProject
    var result = originalText
    // createFileFromText requires read access, and reformat requires write access.
    // Both are satisfied inside WriteCommandAction (which grants read+write).
    // This runs on EDT via invokeAndWait, but crucially NOT inside a smartReadAction,
    // which would deadlock (read lock held + waiting for write lock on EDT).
    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
        val copy = PsiFileFactory.getInstance(project)
          .createFileFromText("_format_tmp.scala", language, originalText)
        CodeStyleManager.getInstance(project).reformat(copy)
        result = copy.getText
      ): Runnable)
    if ApplicationManager.getApplication.isDispatchThread then
      runFormat.run()
    else
      ApplicationManager.getApplication.invokeAndWait(runFormat)
    result

  private def formatRangeOnCopy(
    originalText: String,
    language: com.intellij.lang.Language,
    startOffset: Int,
    endOffset: Int
  ): String =
    val project = projectManager.getProject
    var result = originalText
    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
        val copy = PsiFileFactory.getInstance(project)
          .createFileFromText("_format_tmp.scala", language, originalText)
        CodeStyleManager.getInstance(project).reformatRange(copy, startOffset, endOffset)
        result = copy.getText
      ): Runnable)
    if ApplicationManager.getApplication.isDispatchThread then
      runFormat.run()
    else
      ApplicationManager.getApplication.invokeAndWait(runFormat)
    result

  private def computeFullReplacement(originalText: String, formattedText: String): Seq[TextEdit] =
    val lineCount = originalText.count(_ == '\n') + 1
    val lastLineLength = originalText.length - originalText.lastIndexOf('\n') - 1
    val fullRange = Range(
      Position(0, 0),
      Position(lineCount - 1, lastLineLength)
    )
    Seq(TextEdit(fullRange, formattedText))
