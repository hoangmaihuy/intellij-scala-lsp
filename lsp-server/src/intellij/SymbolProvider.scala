package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.navigation.{ChooseByNameContributor, ChooseByNameContributorEx, NavigationItem}
import com.intellij.psi.*
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FindSymbolParameters
import org.eclipse.lsp4j.{DocumentSymbol, SymbolInformation, Location as LspLocation}

import scala.jdk.CollectionConverters.*

// Implements textDocument/documentSymbol and workspace/symbol.
class SymbolProvider(projectManager: IntellijProjectManager):

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

  def workspaceSymbols(query: String): Seq[SymbolInformation] =
    if query.isEmpty then return Seq.empty

    projectManager.smartReadAction: () =>
      val project = projectManager.getProject
      searchViaContributors(project, query)

  // Use IntelliJ's GotoClassContributor and GotoSymbolContributor extension points.
  // These support prefix/fuzzy matching via processNames + processElementsWithName.
  private def searchViaContributors(project: com.intellij.openapi.project.Project, query: String, seen: scala.collection.mutable.Set[String] = scala.collection.mutable.Set[String]()): Seq[SymbolInformation] =
    try
      val results = Seq.newBuilder[SymbolInformation]
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

      for contributor <- contributors do
        contributor match
          case ex: ChooseByNameContributorEx =>
            // Collect names that match the simple name
            val matchingNames = scala.collection.mutable.ArrayBuffer[String]()
            ex.processNames(
              ((name: String) => {
                if name != null && name.toLowerCase.contains(lowerSimpleName) then
                  matchingNames += name
                true
              }): com.intellij.util.Processor[String],
              scope,
              null
            )

            // For each matching name, collect the navigation items
            for name <- matchingNames.take(100) do // limit to avoid overwhelming results
              val params = FindSymbolParameters.wrap(name, project, true)
              ex.processElementsWithName(
                name,
                ((item: NavigationItem) => {
                  item match
                    case psi: PsiElement =>
                      psi match
                        case named: PsiNamedElement if isSignificantElement(named) =>
                          // If query was FQN, verify the element's FQN matches
                          val fqnMatch = fqnPrefix match
                            case Some(prefix) =>
                              val container = getContainerName(named)
                              container != null && (
                                container == prefix ||
                                container.endsWith("." + prefix) ||
                                prefix.endsWith("." + container)
                              )
                            case None => true

                          if fqnMatch then
                            PsiUtils.elementToLocation(psi).foreach: loc =>
                              val key = s"${named.getName}@${loc.getUri}:${loc.getRange.getStart.getLine}"
                              if !seen.contains(key) then
                                seen += key
                                val kind = PsiUtils.getSymbolKind(named)
                                val containerName = getContainerName(psi)
                                results += new SymbolInformation(named.getName, kind, loc, containerName)
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
                          val fqnMatch = fqnPrefix match
                            case Some(prefix) =>
                              val container = getContainerName(psi)
                              container != null && (
                                container == prefix ||
                                container.endsWith("." + prefix) ||
                                prefix.endsWith("." + container)
                              )
                            case None => true

                          if fqnMatch then
                            PsiUtils.elementToLocation(psi).foreach: loc =>
                              val key = s"${psi.getName}@${loc.getUri}:${loc.getRange.getStart.getLine}"
                              if !seen.contains(key) then
                                seen += key
                                results += new SymbolInformation(psi.getName, PsiUtils.getSymbolKind(psi), loc, getContainerName(psi))
                        case _ => ()
            catch
              case _: Exception => ()

      // Sort: project symbols first, then library symbols (like IntelliJ's "include non-project items")
      val projectFileIndex = ProjectFileIndex.getInstance(project)
      val allResults = results.result()
      val (projectResults, libraryResults) = allResults.partition: sym =>
        val uri = sym.getLocation.getUri
        if uri.startsWith("file://") then
          val path = java.net.URI.create(uri).getPath
          val vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://" + path)
          vf != null && projectFileIndex.isInContent(vf)
        else false
      projectResults ++ libraryResults
    catch
      case e: Exception =>
        System.err.println(s"[SymbolProvider] Error searching workspace symbols: ${e.getMessage}")
        Seq.empty

  private def getContainerName(element: PsiElement): String =
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

    // Fallback: walk up the PSI tree for the nearest significant parent
    var parent = element.getParent
    while parent != null do
      parent match
        case named: PsiNamedElement if isSignificantElement(named) =>
          // Try FQN on the parent
          PsiUtils.getQualifiedNameOf(named) match
            case Some(fqn) => return fqn
            case None => return named.getName
        case _ =>
          parent = parent.getParent
    null
