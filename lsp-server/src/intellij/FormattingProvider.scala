package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.*

class FormattingProvider(projectManager: IntellijProjectManager):

  def getFormatting(uri: String): Seq[TextEdit] =
    try
      projectManager.smartReadAction: () =>
        (for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val originalText = document.getText
          val formattedText = formatOnCopy(psiFile, originalText)
          if formattedText != originalText then
            computeFullReplacement(originalText, formattedText)
          else
            Seq.empty
        ).getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[FormattingProvider] Error: ${e.getMessage}")
        Seq.empty

  def getRangeFormatting(uri: String, range: Range): Seq[TextEdit] =
    try
      projectManager.smartReadAction: () =>
        (for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val originalText = document.getText
          val startOffset = PsiUtils.positionToOffset(document, range.getStart)
          val endOffset = PsiUtils.positionToOffset(document, range.getEnd)
          val formattedText = formatRangeOnCopy(psiFile, originalText, startOffset, endOffset)
          if formattedText != originalText then
            computeFullReplacement(originalText, formattedText)
          else
            Seq.empty
        ).getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[FormattingProvider] Error in range formatting: ${e.getMessage}")
        Seq.empty

  private def formatOnCopy(
    originalFile: com.intellij.psi.PsiFile,
    originalText: String
  ): String =
    val project = projectManager.getProject
    val copy = PsiFileFactory.getInstance(project)
      .createFileFromText("_format_tmp.scala", originalFile.getLanguage, originalText)
    var result = originalText
    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
        CodeStyleManager.getInstance(project).reformat(copy)
        result = copy.getText
      ): Runnable)
    if ApplicationManager.getApplication.isDispatchThread then
      runFormat.run()
    else
      ApplicationManager.getApplication.invokeAndWait(runFormat)
    result

  private def formatRangeOnCopy(
    originalFile: com.intellij.psi.PsiFile,
    originalText: String,
    startOffset: Int,
    endOffset: Int
  ): String =
    val project = projectManager.getProject
    val copy = PsiFileFactory.getInstance(project)
      .createFileFromText("_format_tmp.scala", originalFile.getLanguage, originalText)
    var result = originalText
    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
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
