package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.roots.{OrderRootType, ProjectFileIndex}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{Location, Position, Range, SymbolKind}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

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
    * For JAR-internal files, tries to find the original source from source JARs first,
    * falls back to decompiled text. Caches to a file:// URI so all clients can open it. */
  def elementToLocation(element: PsiElement): Option[Location] =
    // Try to navigate to real source (e.g., from decompiled stub to source JAR)
    val navElement = element.getNavigationElement
    val effectiveElement = if navElement != null && navElement != element then navElement else element

    elementToLocationDirect(effectiveElement)
      .orElse:
        // Fallback for synthetic elements (e.g., case class apply methods) that have no VirtualFile.
        // Walk up the PSI tree and try parent elements, then try the containing class.
        findOriginalElement(element).flatMap(elementToLocationDirect)

  /** Try to find the original source element for a synthetic element.
    * For synthetic methods (like case class apply), navigates to the containing class. */
  private def findOriginalElement(element: PsiElement): Option[PsiElement] =
    // Try 1: If element is a PsiMethod, get its containing class
    element match
      case method: com.intellij.psi.PsiMethod =>
        val cls = method.getContainingClass
        if cls != null then
          val nav = cls.getNavigationElement
          val effective = if nav != null && nav != cls then nav else cls
          if effective.getContainingFile != null && effective.getContainingFile.getVirtualFile != null then
            return Some(effective)
      case _ => ()

    // Try 2: Walk up parents looking for one with a real VirtualFile
    var parent = element.getParent
    var depth = 0
    while parent != null && depth < 10 do
      val nav = parent.getNavigationElement
      val effective = if nav != null && nav != parent then nav else parent
      val hasVf = effective.getContainingFile != null && effective.getContainingFile.getVirtualFile != null
      if hasVf then return Some(effective)
      parent = parent.getParent
      depth += 1

    None

  private def elementToLocationDirect(effectiveElement: PsiElement): Option[Location] =
    for
      file <- Option(effectiveElement.getContainingFile)
      vf <- Option(file.getVirtualFile)
      document <- Option(
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
      )
    yield
      val range = elementToRange(document, effectiveElement)
      val vfPath = vf.getPath
      if vfPath.contains("!/") then
        val cachedUri = cacheJarEntry(file, vf)
        Location(cachedUri, range)
      else
        Location(vfToUri(vf), range)

  /** Resolve a JAR-internal file to a cached file:// URI.
    * First tries to find the original source from attached source JARs (like IntelliJ does),
    * then falls back to IntelliJ's decompiled PSI text. */
  private[intellij] def cacheJarEntry(psiFile: PsiFile, vf: VirtualFile): String =
    try
      val vfPath = vf.getPath
      val separatorIndex = vfPath.indexOf("!/")
      val jarName = Path.of(vfPath.substring(0, separatorIndex)).getFileName.toString
      val entryPath = vfPath.substring(separatorIndex + 2)

      // Determine cache path — preserve original extension for source files,
      // map .class to .scala/.java for decompiled files
      val (cachedEntryPath, isClassFile) = if entryPath.endsWith(".class") then
        (entryPath.replaceAll("\\.class$", ".scala"), true)
      else
        (entryPath, false)
      val cachePath = CACHE_DIR.resolve(jarName).resolve(cachedEntryPath)

      if !Files.exists(cachePath) then
        Files.createDirectories(cachePath.getParent)

        // Try 1: Find original source from attached source JARs
        val sourceText = if isClassFile then findSourceFromSourceJar(vf, entryPath) else None

        val text = sourceText.getOrElse:
          // Try 2: Use PSI text — IntelliJ decompiles .class files into readable source
          val psiText = psiFile.getText
          if psiText != null && psiText.nonEmpty then psiText
          else s"// Could not resolve source: $vfPath"

        Files.writeString(cachePath, text)

      s"file://${cachePath.toAbsolutePath}"
    catch
      case e: Exception =>
        System.err.println(s"[PsiUtils] Failed to cache JAR entry: ${e.getMessage}")
        vfToUri(vf)

  /** Try to find the original source file from source JARs attached to the library.
    * Uses IntelliJ's ProjectFileIndex to find the library, then searches its source roots. */
  private def findSourceFromSourceJar(classVf: VirtualFile, entryPath: String): Option[String] =
    try
      // Get the class root (the JAR containing this .class file)
      val jarPath = classVf.getPath.substring(0, classVf.getPath.indexOf("!/"))
      val classRoot = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
        .findFileByUrl(s"jar://$jarPath!/")

      if classRoot == null then return None

      // Convert .class entry path to potential source paths
      // e.g., com/foo/Bar.class -> com/foo/Bar.scala, com/foo/Bar.java
      // For inner classes: com/foo/Bar$Inner.class -> com/foo/Bar.scala
      val basePath = entryPath.replaceAll("\\$[^/]*\\.class$", "").replaceAll("\\.class$", "")
      val sourceNames = Seq(s"$basePath.scala", s"$basePath.java")

      // Find which library owns this class file via ProjectFileIndex
      val project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects
      if project.isEmpty then return None

      val fileIndex = ProjectFileIndex.getInstance(project.head)
      val orderEntries = fileIndex.getOrderEntriesForFile(classVf)

      // Search source roots of each library order entry
      orderEntries.asScala.flatMap:
        case libEntry: com.intellij.openapi.roots.LibraryOrderEntry =>
          Option(libEntry.getLibrary).flatMap: lib =>
            val sourceRoots = lib.getFiles(OrderRootType.SOURCES)
            sourceRoots.flatMap: sourceRoot =>
              sourceNames.flatMap: sourceName =>
                Option(sourceRoot.findFileByRelativePath(sourceName))
                  .filter(_.isValid)
                  .map: sourceFile =>
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(sourceFile)
                    if doc != null then doc.getText
                    else
                      // Read directly from VFS
                      new String(sourceFile.contentsToByteArray(), sourceFile.getCharset)
            .headOption
        case _ => None
      .headOption
    catch
      case e: Exception =>
        System.err.println(s"[PsiUtils] Source JAR lookup failed: ${e.getMessage}")
        None

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

  /** Walk up from a leaf element to find the nearest reference or named element.
    * Uses IntelliJ's findReferenceAt first for accurate reference detection,
    * then falls back to manual tree walk. */
  def findReferenceElementAt(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    // Try IntelliJ's built-in reference finder first — handles contributed references,
    // injection hosts, and other edge cases that manual walk-up misses
    val builtinRef = Option(psiFile.findReferenceAt(offset))
    if builtinRef.isDefined then
      return builtinRef.map(_.getElement)

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
