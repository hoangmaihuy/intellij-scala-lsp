package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Position, SelectionRange}

// Implements textDocument/selectionRange by walking the PSI tree upward from the cursor.
// Each parent node provides a progressively larger selection range.
class SelectionRangeProvider(projectManager: IntellijProjectManager):

  def getSelectionRanges(uri: String, positions: Seq[Position]): Seq[SelectionRange] =
    ReadAction.compute[Seq[SelectionRange], RuntimeException]: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        positions.map: position =>
          val offset = PsiUtils.positionToOffset(document, position)
          buildSelectionRange(psiFile, document, offset)

      result.getOrElse(positions.map(_ => null))

  private def buildSelectionRange(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    offset: Int
  ): SelectionRange =
    // Find the leaf element at the cursor position
    val leaf = psiFile.findElementAt(offset)
    if leaf == null then return null

    // Walk up the PSI tree, collecting ranges from leaf to root
    val ranges = scala.collection.mutable.ArrayBuffer[org.eclipse.lsp4j.Range]()
    var current: PsiElement = leaf
    while current != null && current != psiFile do
      val textRange = current.getTextRange
      if textRange != null then
        val lspRange = PsiUtils.elementToRange(document, current)
        // Only add if this range is different from the previous one
        if ranges.isEmpty || !rangesEqual(ranges.last, lspRange) then
          ranges += lspRange
      current = current.getParent

    // Add the file-level range as the outermost
    val fileRange = PsiUtils.elementToRange(document, psiFile)
    if ranges.isEmpty || !rangesEqual(ranges.last, fileRange) then
      ranges += fileRange

    // Build the linked list of SelectionRanges from outermost to innermost
    if ranges.isEmpty then null
    else
      var result: SelectionRange = null
      // Iterate from outermost (last) to innermost (first)
      for range <- ranges.reverseIterator do
        val sr = new SelectionRange()
        sr.setRange(range)
        sr.setParent(result)
        result = sr
      result

  private def rangesEqual(a: org.eclipse.lsp4j.Range, b: org.eclipse.lsp4j.Range): Boolean =
    a.getStart.getLine == b.getStart.getLine &&
    a.getStart.getCharacter == b.getStart.getCharacter &&
    a.getEnd.getLine == b.getEnd.getLine &&
    a.getEnd.getCharacter == b.getEnd.getCharacter
