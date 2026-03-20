package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.*

// Handles textDocument/onTypeFormatting for Scala files.
// Uses format-on-copy approach (PsiFileFactory.createFileFromText) to avoid mutating the real document.
//
// Trigger characters: '\n' (newline), '"' (quote), '}' (brace).
class OnTypeFormattingProvider(projectManager: IntellijProjectManager):

  def onTypeFormatting(uri: String, position: Position, ch: String): Seq[TextEdit] =
    try
      ch match
        case "\n" => handleNewline(uri, position)
        case "\"" => handleQuote(uri, position)
        case "}"  => handleBrace(uri, position)
        case _    => Seq.empty
    catch
      case e: Exception =>
        System.err.println(s"[OnTypeFormattingProvider] Error for char '$ch': ${e.getMessage}")
        Seq.empty

  // On newline: reformat the lines around cursor using format-on-copy, return indentation edits.
  private def handleNewline(uri: String, position: Position): Seq[TextEdit] =
    // Collect data inside a read action
    val dataOpt = projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val originalText = document.getText
        val language = psiFile.getLanguage
        (originalText, language)

    dataOpt.map: (originalText, language) =>
      // Format a copy outside the read action
      val project = projectManager.getProject
      var formattedText = originalText

      val runFormat: Runnable = () =>
        WriteCommandAction.runWriteCommandAction(project, (() =>
          val copy = PsiFileFactory.getInstance(project)
            .createFileFromText("_ontypeformat_tmp.scala", language, originalText)
          // Format lines around the cursor (a window of lines)
          val lineCount = originalText.linesIterator.length
          val startLine = math.max(0, position.getLine - 5)
          val endLine = math.min(lineCount - 1, position.getLine + 5)
          val lineStartOffset = offsetOfLine(originalText, startLine)
          val lineEndOff = lineEndOffset(originalText, endLine)
          CodeStyleManager.getInstance(project).reformatRange(copy, lineStartOffset, lineEndOff)
          formattedText = copy.getText
        ): Runnable)

      if ApplicationManager.getApplication.isDispatchThread then
        runFormat.run()
      else
        ApplicationManager.getApplication.invokeAndWait(runFormat)

      if formattedText != originalText then
        computeFullReplacement(originalText, formattedText)
      else
        Seq.empty
    .getOrElse(Seq.empty)

  // On quote: detect if the typed `"` forms a triple-quote `"""`, then auto-close with `"""`.
  private def handleQuote(uri: String, position: Position): Seq[TextEdit] =
    val dataOpt = projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        document.getText

    dataOpt.map: originalText =>
      val pos = position
      val offset = offsetOfPosition(originalText, pos)

      // Check if the three chars ending at 'offset' are `"""`
      // The client has already inserted the typed `"`, so at offset-1 there should be `"`.
      // We look at the text just before the cursor for `"""`.
      if offset >= 3 then
        val before = originalText.substring(offset - 3, offset)
        val after = if offset < originalText.length then originalText.substring(offset, math.min(offset + 3, originalText.length)) else ""
        // If we just formed `"""` and what follows is not `"""` already, auto-close
        if before == "\"\"\"" && after != "\"\"\"" then
          // Insert closing `"""` right at position
          Seq(TextEdit(Range(pos, pos), "\"\"\""))
        else
          Seq.empty
      else
        Seq.empty
    .getOrElse(Seq.empty)

  // On brace: reformat the block containing the closing `}` using format-on-copy.
  private def handleBrace(uri: String, position: Position): Seq[TextEdit] =
    val dataOpt = projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val originalText = document.getText
        val language = psiFile.getLanguage
        (originalText, language)

    dataOpt.map: (originalText, language) =>
      val project = projectManager.getProject
      var formattedText = originalText

      val braceOffset = offsetOfPosition(originalText, position)
      // Find the matching open brace to determine the block range
      val blockStart = findMatchingOpenBrace(originalText, braceOffset)
      val blockEnd = math.min(braceOffset + 1, originalText.length)

      val runFormat: Runnable = () =>
        WriteCommandAction.runWriteCommandAction(project, (() =>
          val copy = PsiFileFactory.getInstance(project)
            .createFileFromText("_ontypeformat_tmp.scala", language, originalText)
          CodeStyleManager.getInstance(project).reformatRange(copy, blockStart, blockEnd)
          formattedText = copy.getText
        ): Runnable)

      if ApplicationManager.getApplication.isDispatchThread then
        runFormat.run()
      else
        ApplicationManager.getApplication.invokeAndWait(runFormat)

      if formattedText != originalText then
        computeFullReplacement(originalText, formattedText)
      else
        Seq.empty
    .getOrElse(Seq.empty)

  // Find the matching open brace for a `}` at closingOffset. Returns closingOffset if not found.
  private def findMatchingOpenBrace(text: String, closingOffset: Int): Int =
    var depth = 0
    var i = math.min(closingOffset, text.length - 1)
    while i >= 0 do
      text.charAt(i) match
        case '}' => depth += 1
        case '{' =>
          depth -= 1
          if depth == 0 then return i
        case _ =>
      i -= 1
    closingOffset // fallback: just reformat from cursor if no match found

  private def offsetOfLine(text: String, line: Int): Int =
    var offset = 0
    var currentLine = 0
    while currentLine < line && offset < text.length do
      if text.charAt(offset) == '\n' then currentLine += 1
      offset += 1
    offset

  private def lineEndOffset(text: String, line: Int): Int =
    val start = offsetOfLine(text, line)
    var offset = start
    while offset < text.length && text.charAt(offset) != '\n' do
      offset += 1
    offset

  private def offsetOfPosition(text: String, pos: Position): Int =
    var offset = 0
    var currentLine = 0
    while currentLine < pos.getLine && offset < text.length do
      if text.charAt(offset) == '\n' then currentLine += 1
      offset += 1
    // Now advance by character within the line
    math.min(offset + pos.getCharacter, text.length)

  private def computeFullReplacement(originalText: String, formattedText: String): Seq[TextEdit] =
    val lineCount = originalText.count(_ == '\n') + 1
    val lastLineLength = originalText.length - originalText.lastIndexOf('\n') - 1
    val fullRange = Range(
      Position(0, 0),
      Position(lineCount - 1, lastLineLength)
    )
    Seq(TextEdit(fullRange, formattedText))
