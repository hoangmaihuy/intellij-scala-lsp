package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.navigation.{ChooseByNameContributor, ChooseByNameContributorEx, GotoClassContributor, NavigationItem}
import com.intellij.psi.*
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FindSymbolParameters
import org.eclipse.lsp4j.{DocumentSymbol, SymbolInformation, Location as LspLocation}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

// Implements textDocument/documentSymbol and workspace/symbol.
class SymbolProvider(projectManager: IntellijProjectManager):

  private enum MatchRelevance(val rank: Int):
    case Exact extends MatchRelevance(0)
    case ExactCaseInsensitive extends MatchRelevance(1)
    case Prefix extends MatchRelevance(2)
    case CamelCase extends MatchRelevance(3)
    case Substring extends MatchRelevance(4)

  private def scoreMatch(symbolName: String, query: String): Option[MatchRelevance] =
    if symbolName == query then Some(MatchRelevance.Exact)
    else if symbolName.equalsIgnoreCase(query) then Some(MatchRelevance.ExactCaseInsensitive)
    else if symbolName.toLowerCase.startsWith(query.toLowerCase) then Some(MatchRelevance.Prefix)
    else if matchesCamelCase(symbolName, query) then Some(MatchRelevance.CamelCase)
    else if symbolName.toLowerCase.contains(query.toLowerCase) then Some(MatchRelevance.Substring)
    else None

  private def matchesCamelCase(name: String, query: String): Boolean =
    try
      import com.intellij.psi.codeStyle.NameUtil
      val matcher = NameUtil.buildMatcher(query).build()
      matcher.matches(name)
    catch
      case _: Exception =>
        val initials = name.filter(_.isUpper).map(_.toLower)
        val queryLower = query.toLowerCase
        queryLower.forall(c => initials.contains(c))

  // --- textDocument/documentSymbol ---

  def documentSymbols(uri: String): Seq[DocumentSymbol] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        collectSymbols(psiFile, document)

      result.getOrElse(Seq.empty)

  // Recursively collect symbols from PSI children.
  // Each significant element becomes a DocumentSymbol with its own children.
  // Non-significant elements are transparent — their children are promoted
  // to be children of the nearest significant ancestor.
  private def collectSymbols(parent: PsiElement, document: com.intellij.openapi.editor.Document): Seq[DocumentSymbol] =
    val result = Seq.newBuilder[DocumentSymbol]
    for child <- parent.getChildren do
      child match
        case named: PsiNamedElement if isSignificantElement(named) =>
          val name = Option(named.getName).getOrElse("<anonymous>")
          val kind = PsiUtils.getSymbolKind(named)
          val range = PsiUtils.elementToRange(document, child)
          val selectionRange = PsiUtils.nameElementToRange(document, child)
          val symbol = new DocumentSymbol(name, kind, range, selectionRange)

          // Recurse into this significant element for its children
          val childSymbols = collectSymbols(child, document)
          if childSymbols.nonEmpty then
            symbol.setChildren(childSymbols.asJava)

          result += symbol

        case _ =>
          // Non-significant element: promote its significant descendants
          // as siblings at this level
          result ++= collectSymbols(child, document)
    result.result()

  private def isSignificantElement(element: PsiNamedElement): Boolean =
    val name = element.getName
    if name == null || name.isEmpty then return false
    ScalaTypes.isTypeDefinition(element) || ScalaTypes.isFunction(element) ||
    ScalaTypes.isValue(element) || ScalaTypes.isVariable(element) ||
    ScalaTypes.isTypeAlias(element) || ScalaTypes.isPackaging(element) ||
    element.isInstanceOf[PsiClass] || element.isInstanceOf[PsiMethod]

  // --- workspace/symbol ---

  def workspaceSymbols(query: String, cancelled: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)): Seq[SymbolInformation] =
    if query.isEmpty || cancelled.get() then return Seq.empty

    projectManager.smartReadAction: () =>
      searchViaContributors(projectManager.getProject, query, cancelled)

  // Use IntelliJ's GotoClassContributor and GotoSymbolContributor extension points.
  // These support prefix/fuzzy matching via processNames + processElementsWithName.
  private def searchViaContributors(project: com.intellij.openapi.project.Project, query: String, cancelled: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)): Seq[SymbolInformation] =
    try
      val results = ArrayBuffer[(SymbolInformation, MatchRelevance)]()
      val seen = new java.util.concurrent.ConcurrentHashMap[String, MatchRelevance]()
      val scope = GlobalSearchScope.allScope(project)

      // For fully qualified names like "io.circe.Json", extract the simple name
      // for name matching, and use the full query to post-filter by FQN.
      val lastDot = query.lastIndexOf('.')
      val (simpleName, fqnPrefix) =
        if lastDot > 0 then (query.substring(lastDot + 1), Some(query.substring(0, lastDot)))
        else (query, None)
      val lowerSimpleName = simpleName.toLowerCase

      // Collect from both CLASS and SYMBOL extension points
      val contributors =
        ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala ++
        ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList.asScala

      for contributor <- contributors if !cancelled.get() do
        contributor match
          case ex: ChooseByNameContributorEx =>
            // Collect names that match the simple name
            val matchingNames = ArrayBuffer[String]()
            ex.processNames(
              ((name: String) => {
                if cancelled.get() then false
                else
                  if name != null && name.toLowerCase.contains(lowerSimpleName) then
                    matchingNames += name
                  true
              }): com.intellij.util.Processor[String],
              scope,
              null
            )

            // For each matching name, collect the navigation items
            for name <- matchingNames.take(100) if !cancelled.get() do // limit to avoid overwhelming results
              val params = FindSymbolParameters.wrap(name, project, true)
              ex.processElementsWithName(
                name,
                ((item: NavigationItem) => {
                  if cancelled.get() then false
                  else
                    item match
                      case psi: PsiElement =>
                        val unwrapped = PsiUtils.unwrapSyntheticElement(psi)
                        unwrapped match
                          case named: PsiNamedElement if isSignificantElement(named) =>
                            val container = getContainerName(named, item, contributor)
                            // If query was FQN, verify the element's FQN matches
                            val fqnMatch = fqnPrefix match
                              case Some(prefix) =>
                                container != null && (
                                  container == prefix ||
                                  container.endsWith("." + prefix) ||
                                  prefix.endsWith("." + container)
                                )
                              case None => true

                            if fqnMatch then
                              scoreMatch(named.getName, simpleName).foreach: relevance =>
                                val containerName = if container != null then container else getContainerName(unwrapped, item, contributor)
                                val rawQualKey = Option(containerName).filter(_.nonEmpty).map(c => s"$c.${named.getName}").getOrElse(named.getName)
                                val qualKey = if rawQualKey.endsWith("$") then rawQualKey.stripSuffix("$") else rawQualKey
                                val existing = seen.get(qualKey)
                                if existing == null || relevance.rank < existing.rank then
                                  PsiUtils.elementToLocation(unwrapped).foreach: loc =>
                                    seen.put(qualKey, relevance)
                                    val idx = results.indexWhere: (sym, _) =>
                                      val storedKey = Option(sym.getContainerName).filter(_.nonEmpty)
                                        .map(c => s"$c.${sym.getName}").getOrElse(sym.getName).stripSuffix("$")
                                      storedKey == qualKey
                                    if idx >= 0 then results.remove(idx)
                                    val kind = PsiUtils.getSymbolKind(named)
                                    results += ((new SymbolInformation(named.getName, kind, loc, containerName), relevance))
                          case _ => ()
                      case _ => ()
                    true
                }): com.intellij.util.Processor[NavigationItem],
                params
              )

          case legacy =>
            // Fallback for non-Ex contributors: use getNames/getItemsByName
            try
              val names = legacy.getNames(project, true)
              if names != null then
                for name <- names if name != null && name.toLowerCase.contains(lowerSimpleName) do
                  val items = legacy.getItemsByName(name, simpleName, project, true)
                  if items != null then
                    for item <- items.take(20) do
                      item match
                        case psi: PsiNamedElement if isSignificantElement(psi) =>
                          val container = getContainerName(psi, item, legacy)
                          val fqnMatch = fqnPrefix match
                            case Some(prefix) =>
                              container != null && (
                                container == prefix ||
                                container.endsWith("." + prefix) ||
                                prefix.endsWith("." + container)
                              )
                            case None => true

                          if fqnMatch then
                            val unwrapped = PsiUtils.unwrapSyntheticElement(psi)
                            unwrapped match
                              case namedUnwrapped: PsiNamedElement =>
                                scoreMatch(namedUnwrapped.getName, simpleName).foreach: relevance =>
                                  val containerName = if container != null then container else getContainerName(unwrapped, item, legacy)
                                  val rawQualKey = Option(containerName).filter(_.nonEmpty).map(c => s"$c.${namedUnwrapped.getName}").getOrElse(namedUnwrapped.getName)
                                  val qualKey = if rawQualKey.endsWith("$") then rawQualKey.stripSuffix("$") else rawQualKey
                                  val existing = seen.get(qualKey)
                                  if existing == null || relevance.rank < existing.rank then
                                    PsiUtils.elementToLocation(unwrapped).foreach: loc =>
                                      seen.put(qualKey, relevance)
                                      val idx = results.indexWhere: (sym, _) =>
                                        val storedKey = Option(sym.getContainerName).filter(_.nonEmpty)
                                          .map(c => s"$c.${sym.getName}").getOrElse(sym.getName).stripSuffix("$")
                                        storedKey == qualKey
                                      if idx >= 0 then results.remove(idx)
                                      results += ((new SymbolInformation(namedUnwrapped.getName, PsiUtils.getSymbolKind(namedUnwrapped), loc, containerName), relevance))
                              case _ => ()
                        case _ => ()
            catch
              case _: Exception => ()

      if cancelled.get() then return Seq.empty

      // Sort by relevance, then project-first, then name
      val projectFileIndex = ProjectFileIndex.getInstance(project)
      val allResults = results.toSeq
      allResults.sortBy: (sym, relevance) =>
        val uri = sym.getLocation.getUri
        val isProject =
          if uri.startsWith("file://") then
            val path = java.net.URI.create(uri).getPath
            val vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://" + path)
            vf != null && projectFileIndex.isInContent(vf)
          else false
        (relevance.rank, if isProject then 0 else 1, sym.getName)
      .map(_._1)
    catch
      case e: Exception =>
        System.err.println(s"[SymbolProvider] Error searching workspace symbols: ${e.getMessage}")
        Seq.empty

  /** Get the container from a GotoClassContributor's qualified name.
    * E.g., if contributor says qualified name is "com.example.Foo.bar",
    * and the item name is "bar", the container is "com.example.Foo". */
  private def getContainerFromContributor(item: NavigationItem, contributor: ChooseByNameContributor): String =
    contributor match
      case gcc: GotoClassContributor =>
        val qn = gcc.getQualifiedName(item)
        if qn != null then
          val sep = gcc.getQualifiedNameSeparator
          if sep != null then
            val lastSep = qn.lastIndexOf(sep)
            if lastSep > 0 then return qn.substring(0, lastSep)
        null
      case _ => null

  private def getContainerName(element: PsiElement, item: NavigationItem = null, contributor: ChooseByNameContributor = null): String =
    // Try to derive container FQN from the element's own FQN
    // e.g., for "io.circe.Json", containerName = "io.circe"
    element match
      case named: PsiNamedElement =>
        val name = named.getName
        PsiUtils.getQualifiedNameOf(named) match
          case Some(fqn) if fqn.endsWith("." + name) =>
            return fqn.stripSuffix("." + name)
          case _ => ()
      case _ => ()

    // Try PsiMethod.getContainingClass
    element match
      case method: PsiMethod =>
        try
          val cls = method.getContainingClass
          if cls != null then
            val fqn = cls.getQualifiedName
            if fqn != null && fqn.nonEmpty then return fqn
            val name = cls.getName
            if name != null && name.nonEmpty then return name
        catch case _: Exception => ()
      case _ => ()

    // For stub-based elements (from index), the parent chain may be incomplete.
    // Resolve through the file's full PSI tree to find the enclosing class/object.
    try
      val psiFile = element.getContainingFile
      if psiFile != null then
        val offset = element.getTextOffset
        if offset >= 0 then
          val elementInFile = psiFile.findElementAt(offset)
          if elementInFile != null then
            var p = elementInFile.getParent
            while p != null && p != psiFile do
              if ScalaTypes.isClassLike(p) || ScalaTypes.isTypeDefinition(p) then
                PsiUtils.getQualifiedNameOf(p) match
                  case Some(fqn) if fqn.nonEmpty => return fqn
                  case _ =>
                    p match
                      case named: PsiNamedElement =>
                        val name = named.getName
                        if name != null && name.nonEmpty then return name
                      case _ => ()
              p = p.getParent
    catch case _: Exception => ()

    // Try the contributor's own qualified name resolution
    if item != null && contributor != null then
      val fromContrib = getContainerFromContributor(item, contributor)
      if fromContrib != null then return fromContrib

    // Walk up the PSI tree for the nearest significant parent
    var parent = element.getParent
    while parent != null do
      parent match
        case named: PsiNamedElement if isSignificantElement(named) =>
          PsiUtils.getQualifiedNameOf(named) match
            case Some(fqn) => return fqn
            case None => return named.getName
        case _ =>
          parent = parent.getParent
    null
