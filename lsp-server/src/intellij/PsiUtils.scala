package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.roots.{OrderRootType, ProjectFileIndex}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiClass, PsiElement, PsiField, PsiFile, PsiMethod, PsiNameIdentifierOwner, PsiNamedElement}
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

  /** Compute an LSP Range from raw text and byte offsets (when no Document is available).
    * Used for library elements in JARs where FileDocumentManager returns null. */
  private[intellij] def offsetToRange(text: String, startOffset: Int, endOffset: Int): Range =
    if text == null || text.isEmpty then return Range(Position(0, 0), Position(0, 0))
    var line = 0
    var lineStart = 0
    var startPos: Position = null
    var i = 0
    while i <= math.min(endOffset, text.length) do
      if i == startOffset then
        startPos = Position(line, i - lineStart)
      if i == endOffset then
        val endPos = Position(line, i - lineStart)
        if startPos == null then startPos = endPos
        return Range(startPos, endPos)
      if i < text.length && text.charAt(i) == '\n' then
        line += 1
        lineStart = i + 1
      i += 1
    // Fallback: offset beyond text
    val pos = Position(line, 0)
    if startPos == null then startPos = pos
    Range(startPos, pos)

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
    // Try 1: If element has getContainingClass (methods, fields), navigate to the class
    val containingClass = element match
      case m: PsiMethod => Option(m.getContainingClass)
      case f: PsiField  => Option(f.getContainingClass)
      case _            => None

    val fromClass = containingClass.flatMap: cls =>
      val nav = cls.getNavigationElement
      val effective = if nav != null && nav != cls then nav else cls
      if effective.getContainingFile != null && effective.getContainingFile.getVirtualFile != null then Some(effective)
      else None

    if fromClass.isDefined then return fromClass

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
    try
      for
        file <- Option(effectiveElement.getContainingFile)
        vf <- Option(file.getVirtualFile)
        location <- {
          val vfPath = vf.getPath
          if vfPath.contains("!/") then
            // JAR-internal files: cache source, compute range with Document fallback
            val cachedUri = cacheJarEntry(file, vf)
            val range = Option(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf))
              .map(doc => elementToRange(doc, effectiveElement))
              .getOrElse(offsetToRange(file.getText, effectiveElement.getTextRange.getStartOffset, effectiveElement.getTextRange.getEndOffset))
            Some(Location(cachedUri, range))
          else
            // Local files: Document required
            Option(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf))
              .map: document =>
                Location(vfToUri(vf), elementToRange(document, effectiveElement))
        }
      yield location
    catch
      case e: Exception =>
        System.err.println(s"[PsiUtils] Skipping element due to stale index: ${e.getMessage}")
        None

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
      // map .class/.tasty to .scala for decompiled files
      val isBinaryFile = entryPath.endsWith(".class") || entryPath.endsWith(".tasty")
      val cachedEntryPath = if entryPath.endsWith(".class") then
        entryPath.replaceAll("\\.class$", ".scala")
      else if entryPath.endsWith(".tasty") then
        entryPath.replaceAll("\\.tasty$", ".scala")
      else
        entryPath
      val cachePath = CACHE_DIR.resolve(jarName).resolve(cachedEntryPath)

      if !Files.exists(cachePath) then
        Files.createDirectories(cachePath.getParent)

        // Try 1: Find original source from attached source JARs
        val sourceText = if isBinaryFile then findSourceFromSourceJar(vf, entryPath) else None

        val text = sourceText.getOrElse:
          // Try 2: Use PSI text — IntelliJ decompiles .class/.tasty files into readable source
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
  private def findSourceFromSourceJar(binaryVf: VirtualFile, entryPath: String): Option[String] =
    try
      // Get the JAR root containing this binary file
      val jarPath = binaryVf.getPath.substring(0, binaryVf.getPath.indexOf("!/"))
      val classRoot = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
        .findFileByUrl(s"jar://$jarPath!/")

      if classRoot == null then return None

      // Convert .class/.tasty entry path to potential source paths
      // e.g., com/foo/Bar.class -> com/foo/Bar.scala, com/foo/Bar.java
      //       com/foo/Bar$package.tasty -> com/foo/Bar$package.scala
      // For inner classes: com/foo/Bar$Inner.class -> com/foo/Bar.scala
      val basePath = entryPath
        .replaceAll("\\.tasty$", "")
        .replaceAll("\\$[^/]*\\.class$", "")
        .replaceAll("\\.class$", "")
      // For $package files (Scala 3 top-level definitions), also try without $package suffix
      val baseWithoutPackage = basePath.replaceAll("\\$package$", "")
      val sourceNames = (Seq(s"$basePath.scala", s"$basePath.java") ++
        (if basePath != baseWithoutPackage then Seq(s"$baseWithoutPackage.scala") else Seq.empty)
      ).distinct

      // Find which library owns this class file via ProjectFileIndex
      val project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects
      if project.isEmpty then return None

      val fileIndex = ProjectFileIndex.getInstance(project.head)
      val orderEntries = fileIndex.getOrderEntriesForFile(binaryVf)

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

  /** Determine the LSP SymbolKind for a PSI element.
   * Shared by SymbolProvider, CompletionProvider, and CallHierarchyProvider. */
  def getSymbolKind(element: PsiElement): SymbolKind =
    if ScalaTypes.isEnum(element) then SymbolKind.Enum
    else if ScalaTypes.isClass(element) then SymbolKind.Class
    else if ScalaTypes.isTrait(element) then SymbolKind.Interface
    else if ScalaTypes.isObject(element) then SymbolKind.Module
    else if ScalaTypes.isFunction(element) then SymbolKind.Method
    else if ScalaTypes.isValue(element) || ScalaTypes.isPatternDefinition(element) then SymbolKind.Field
    else if ScalaTypes.isVariable(element) || ScalaTypes.isVariableDefinition(element) then SymbolKind.Variable
    // Binding patterns from StructureView — check isVal/isVar to distinguish
    else if ScalaTypes.isBindingPattern(element) then
      if ScalaTypes.isVar(element) then SymbolKind.Variable else SymbolKind.Field
    else if ScalaTypes.isTypeAlias(element) then SymbolKind.TypeParameter
    else if ScalaTypes.isPackaging(element) then SymbolKind.Package
    else if element.isInstanceOf[PsiMethod] then SymbolKind.Method
    else if element.isInstanceOf[PsiClass] then SymbolKind.Class
    else if element.isInstanceOf[PsiField] then SymbolKind.Field
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

  /** Like resolveToDeclaration but returns all resolved targets for poly-variant references.
    * Used by ReferencesProvider to search references for all overloaded targets. */
  def resolveToDeclarations(psiFile: PsiFile, offset: Int): Seq[PsiElement] =
    findReferenceElementAt(psiFile, offset).toSeq.flatMap: element =>
      val ref = element.getReference
      if ref != null then
        ref match
          case poly: com.intellij.psi.PsiPolyVariantReference =>
            val results = poly.multiResolve(false)
            val resolved = results.flatMap(r => Option(r.getElement)).toSeq
            if resolved.nonEmpty then resolved
            else Option(ref.resolve()).toSeq
          case _ =>
            Option(ref.resolve()).toSeq
      else
        var parent = element.getParent
        while parent != null && !parent.isInstanceOf[PsiNamedElement] do
          parent = parent.getParent
        if parent != null then Seq(parent) else Seq(element)

  /** Classify a reference element's usage type by checking ancestors. */
  def getUsageType(element: PsiElement): String =
    var current = element.getParent
    var depth = 0
    while current != null && depth < 15 do
      val className = current.getClass.getName
      if className.contains("ImportStatement") || className.contains("ScImportExpr") then
        return ReferenceResult.Import
      if className.contains("ScAssignment") then
        val children = current.getChildren
        if children.nonEmpty && children.head.getTextRange.contains(element.getTextRange) then
          return ReferenceResult.Write
      if className.contains("ScVariableDefinition") then
        return ReferenceResult.Write
      if className.contains("ScSimpleTypeElement") || className.contains("ScParameterizedTypeElement") then
        return ReferenceResult.TypeRef
      if className.contains("ScPattern") || className.contains("ScCaseClause") then
        return ReferenceResult.Pattern
      current = current.getParent
      depth += 1
    ReferenceResult.Read

  /** Unwrap IntelliJ synthetic wrappers to get the real Scala element.
    * Handles PsiClassWrapper, PsiMethodWrapper, FakePsiMethod. */
  def unwrapSyntheticElement(element: PsiElement): PsiElement =
    val className = element.getClass.getName
    if className.contains("PsiClassWrapper") || className.contains("PsiMethodWrapper") || className.contains("FakePsiMethod") then
      try
        val methods = Seq("delegate", "method", "getNavigationElement")
        methods.flatMap: methodName =>
          try Some(element.getClass.getMethod(methodName).invoke(element).asInstanceOf[PsiElement])
          catch case _: Exception => None
        .headOption.getOrElse(element)
      catch
        case _: Exception => element
    else element

  /** Check if a URI points to a cached source file (external dependency). */
  def isCachedSourceFile(uri: String): Boolean =
    val path = if uri.startsWith("file://") then java.net.URI.create(uri).getPath else uri
    path.startsWith(CACHE_DIR.toString)

  /** For elements from cached source files, find the real library PsiElement via IntelliJ's index.
    * Cached file elements are disconnected from the index, so ReferencesSearch/DefinitionsSearch
    * won't find anything. This maps them back to the original library element by FQN.
    * Uses reflection for JavaPsiFacade to avoid classloader isolation issues in daemon mode. */
  def resolveLibraryElement(cachedElement: PsiElement): Option[PsiElement] =
    try
      cachedElement match
        case named: PsiNamedElement =>
          val name = named.getName
          if name == null then return None

          val project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects.headOption
          if project.isEmpty then return None

          val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project.get)

          // Extract package from file text
          val containingFile = cachedElement.getContainingFile
          if containingFile == null then return None
          val text = containingFile.getText
          val packageName = text.linesIterator
            .map(_.trim)
            .find(_.startsWith("package "))
            .map(_.stripPrefix("package ").trim)
            .getOrElse("")

          val fqn = if packageName.nonEmpty then s"$packageName.$name" else name
          System.err.println(s"[PsiUtils] Resolving cached element to library: $fqn")

          // Use reflection to call JavaPsiFacade.findClass — the facade class lives in the
          // Java plugin classloader, different from our classloader in daemon mode
          findClassByFqn(project.get, fqn, scope)
            .orElse(findClassByFqn(project.get, fqn + "$", scope))
        case _ => None
    catch
      case e: Exception =>
        System.err.println(s"[PsiUtils] Failed to resolve library element: ${e.getMessage}")
        None

  /** Find a class by FQN using the ChooseByNameContributor approach (same as SymbolProvider).
    * Uses reflection for getQualifiedName to avoid classloader isolation issues. */
  private def findClassByFqn(
    project: com.intellij.openapi.project.Project,
    fqn: String,
    scope: com.intellij.psi.search.GlobalSearchScope,
  ): Option[PsiElement] =
    try
      import com.intellij.navigation.ChooseByNameContributor
      import com.intellij.util.indexing.FindSymbolParameters
      val rawShortName = fqn.split('.').last
      // IntelliJ indexes Scala companion objects without the JVM '$' suffix
      val shortName = rawShortName.stripSuffix("$")
      val fqnWithout$ = fqn.stripSuffix("$")
      val contributors =
        ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala ++
        ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList.asScala

      val params = FindSymbolParameters.wrap(shortName, project, true)
      var result: Option[PsiElement] = None
      val iter = contributors.iterator
      while result.isEmpty && iter.hasNext do
        iter.next() match
          case ex: com.intellij.navigation.ChooseByNameContributorEx =>
            ex.processElementsWithName(
              shortName,
              ((item: com.intellij.navigation.NavigationItem) => {
                if result.isEmpty then
                  item match
                    case psi: PsiElement =>
                      // Use reflection to get qualifiedName — works across classloaders
                      val qualName = getQualifiedName(psi)
                      if qualName.nonEmpty && (qualName.get == fqn || qualName.get == fqnWithout$) then
                        result = Some(psi)
                    case _ => ()
                result.isEmpty
              }): com.intellij.util.Processor[com.intellij.navigation.NavigationItem],
              params,
            )
          case _ => ()
      if result.isEmpty then
        System.err.println(s"[PsiUtils] findClassByFqn($fqn): not found via ${contributors.size} contributors")
      result
    catch
      case e: Exception =>
        System.err.println(s"[PsiUtils] findClassByFqn($fqn) failed: ${e.getMessage}")
        None

  /** Public accessor for getting the qualified name of an element. */
  def getQualifiedNameOf(element: PsiElement): Option[String] = getQualifiedName(element)

  /** Get qualified name via reflection to avoid classloader issues.
    * Works for PsiClass, ScTypeDefinition, and any element with a getQualifiedName method. */
  private def getQualifiedName(element: PsiElement): Option[String] =
    try
      val method = element.getClass.getMethod("getQualifiedName")
      Option(method.invoke(element)).map(_.toString)
    catch
      case _: NoSuchMethodException => None
      case _: Exception => None
