package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{Location, Position, Range}

/**
 * Utilities for converting between IntelliJ's offset-based positions
 * and LSP's line:character positions.
 */
object PsiUtils:

  /** Convert LSP Position (line, character) to IntelliJ absolute offset */
  def positionToOffset(document: Document, position: Position): Int =
    val line = math.min(position.getLine, document.getLineCount - 1)
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    val offset = lineStart + position.getCharacter
    math.min(offset, lineEnd)

  /** Convert IntelliJ absolute offset to LSP Position */
  def offsetToPosition(document: Document, offset: Int): Position =
    val clampedOffset = math.max(0, math.min(offset, document.getTextLength))
    val line = document.getLineNumber(clampedOffset)
    val lineStart = document.getLineStartOffset(line)
    Position(line, clampedOffset - lineStart)

  /** Convert a PsiElement's text range to an LSP Range */
  def elementToRange(document: Document, element: PsiElement): Range =
    val start = offsetToPosition(document, element.getTextRange.getStartOffset)
    val end = offsetToPosition(document, element.getTextRange.getEndOffset)
    Range(start, end)

  /** Convert a PsiElement to an LSP Location (file URI + range) */
  def elementToLocation(element: PsiElement): Option[Location] =
    for
      file <- Option(element.getContainingFile)
      vf <- Option(file.getVirtualFile)
      document <- Option(
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
      )
    yield
      val uri = vfToUri(vf)
      val range = elementToRange(document, element)
      Location(uri, range)

  /** Get the name range for a named element (just the identifier, not the whole declaration) */
  def nameElementToRange(document: Document, element: PsiElement): Range =
    element match
      case named: PsiNameIdentifierOwner =>
        Option(named.getNameIdentifier) match
          case Some(nameId) => elementToRange(document, nameId)
          case None => elementToRange(document, element)
      case _ =>
        elementToRange(document, element)

  /** Convert a VirtualFile to a file:// URI */
  def vfToUri(vf: VirtualFile): String =
    s"file://${vf.getPath}"

  /** Find the PsiElement at a given offset that is most suitable for navigation */
  def findElementAtOffset(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    Option(psiFile.findElementAt(offset))

  /** Walk up from a leaf element to find the nearest reference or named element */
  def findReferenceElementAt(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    findElementAtOffset(psiFile, offset).map: leaf =>
      // Walk up to find element with a reference
      var current = leaf
      while current != null && current.getReference == null && current != psiFile do
        current = current.getParent
      if current != null && current != psiFile then current else leaf
