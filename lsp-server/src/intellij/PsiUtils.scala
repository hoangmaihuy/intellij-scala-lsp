package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{Location, Position, Range, SymbolKind}

import java.nio.file.{Files, Path}

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

  private val CACHE_DIR: Path = Path.of(System.getProperty("user.home"), ".cache", "intellij-scala-lsp", "sources")

  /** Convert a PsiElement to an LSP Location (file URI + range).
    * For JAR-internal files, extracts the decompiled/source text to a cache directory
    * and returns a file:// URI so clients can open it directly. */
  def elementToLocation(element: PsiElement): Option[Location] =
    for
      file <- Option(element.getContainingFile)
      vf <- Option(file.getVirtualFile)
      document <- Option(
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
      )
    yield
      val range = elementToRange(document, element)
      val vfPath = vf.getPath
      if vfPath.contains("!/") then
        // JAR-internal file — cache the decompiled text and return file:// URI
        val cachedUri = cacheJarEntry(file, vf)
        Location(cachedUri, range)
      else
        Location(vfToUri(vf), range)

  /** Extract a JAR-internal file's content to the cache directory.
    * Uses IntelliJ's decompiled PSI text (which is already available for .class files). */
  private[intellij] def cacheJarEntry(psiFile: PsiFile, vf: VirtualFile): String =
    try
      val vfPath = vf.getPath
      val separatorIndex = vfPath.indexOf("!/")
      val jarName = Path.of(vfPath.substring(0, separatorIndex)).getFileName.toString
      val entryPath = vfPath.substring(separatorIndex + 2)

      // Create a stable cache path: ~/.cache/intellij-scala-lsp/sources/<jar-name>/<entry-path>
      // Map .class to .scala for decompiled Scala, .java for Java
      val cachedEntryPath = if entryPath.endsWith(".class") then
        entryPath.replaceAll("\\.class$", ".scala")
      else entryPath
      val cachePath = CACHE_DIR.resolve(jarName).resolve(cachedEntryPath)

      if !Files.exists(cachePath) then
        Files.createDirectories(cachePath.getParent)
        // Use PSI text — IntelliJ already decompiles .class files into readable source
        val text = psiFile.getText
        if text != null && text.nonEmpty then
          Files.writeString(cachePath, text)
        else
          Files.writeString(cachePath, s"// Could not decompile: $vfPath")

      s"file://${cachePath.toAbsolutePath}"
    catch
      case e: Exception =>
        System.err.println(s"[PsiUtils] Failed to cache JAR entry: ${e.getMessage}")
        // Fallback to jar: URI
        vfToUri(vf)

  /** Get the name range for a named element (just the identifier, not the whole declaration) */
  def nameElementToRange(document: Document, element: PsiElement): Range =
    element match
      case named: PsiNameIdentifierOwner =>
        Option(named.getNameIdentifier) match
          case Some(nameId) => elementToRange(document, nameId)
          case None => elementToRange(document, element)
      case _ =>
        elementToRange(document, element)

  /** Convert a VirtualFile to a URI string.
    * For local files: file:///path/to/file
    * For JAR-internal files: jar:file:///path/to.jar!/internal/path */
  def vfToUri(vf: VirtualFile): String =
    val path = vf.getPath
    if path.contains("!/") then
      // JAR-internal file — split into jar path and entry path
      val separatorIndex = path.indexOf("!/")
      val jarPath = path.substring(0, separatorIndex)
      val entryPath = path.substring(separatorIndex)
      s"jar:file://$jarPath$entryPath"
    else
      s"file://$path"

  /** Find the PsiElement at a given offset that is most suitable for navigation */
  def findElementAtOffset(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    Option(psiFile.findElementAt(offset))

  /** Determine the LSP SymbolKind for a PSI element based on its class name.
   * Shared by SymbolProvider, CompletionProvider, and CallHierarchyProvider. */
  def getSymbolKind(element: PsiElement): SymbolKind =
    val cls = element.getClass.getName
    if cls.contains("ScClass") then SymbolKind.Class
    else if cls.contains("ScTrait") then SymbolKind.Interface
    else if cls.contains("ScObject") then SymbolKind.Module
    else if cls.contains("ScFunction") || cls.contains("PsiMethod") then SymbolKind.Method
    else if cls.contains("ScValue") || cls.contains("ScPatternDefinition") then SymbolKind.Variable
    else if cls.contains("ScVariable") || cls.contains("ScVariableDefinition") then SymbolKind.Variable
    else if cls.contains("ScTypeAlias") then SymbolKind.TypeParameter
    else if cls.contains("ScPackaging") then SymbolKind.Package
    else if cls.contains("PsiClass") then SymbolKind.Class
    else if cls.contains("PsiField") then SymbolKind.Field
    else SymbolKind.Variable

  /** Walk up from a leaf element to find the nearest reference or named element */
  def findReferenceElementAt(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    findElementAtOffset(psiFile, offset).map: leaf =>
      // Walk up to find element with a reference
      var current = leaf
      while current != null && current.getReference == null && current != psiFile do
        current = current.getParent
      if current != null && current != psiFile then current else leaf

  /** Resolve element at offset to its declaration. If on a reference, resolves it.
    * If on a declaration itself, walks up to the containing PsiNamedElement. */
  def resolveToDeclaration(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    findReferenceElementAt(psiFile, offset).flatMap: element =>
      val ref = element.getReference
      if ref != null then
        Option(ref.resolve())
      else
        var parent = element.getParent
        while parent != null && !parent.isInstanceOf[PsiNamedElement] do
          parent = parent.getParent
        if parent != null then Some(parent) else Some(element)
