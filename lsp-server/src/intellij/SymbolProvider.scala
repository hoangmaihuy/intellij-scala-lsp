package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.ide.util.gotoByName.{ChooseByNameModel, ChooseByNameViewModel, DefaultChooseByNameItemProvider, GotoClassModel2, GotoSymbolModel2}
import com.intellij.navigation.NavigationItem
import com.intellij.psi.*
import org.eclipse.lsp4j.{DocumentSymbol, SymbolInformation}

import scala.collection.mutable.ArrayBuffer
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

  def workspaceSymbols(query: String, cancelled: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)): Seq[SymbolInformation] =
    if query.isEmpty || cancelled.get() then return Seq.empty

    projectManager.smartReadAction: () =>
      searchViaIntelliJ(projectManager.getProject, query, cancelled)

  private val MaxResults = 200

  // Delegate to IntelliJ's DefaultChooseByNameItemProvider + GotoSymbolModel2.
  // This handles name matching, contributor coordination, FQN resolution,
  // and avoids stub index nesting deadlocks.
  // For FQN queries like "Container.method", we search by simple name then post-filter
  // by container, since IntelliJ's symbol contributors may not provide method-level FQNs.
  // Delegate to IntelliJ's DefaultChooseByNameItemProvider + GotoClassModel2/GotoSymbolModel2.
  // For FQN queries containing dots, IntelliJ handles the FQN matching internally.
  // VS Code fuzzy-matches the query against result names, so for FQN results we include
  // the container's simple name in the symbol name (e.g., "Container.member") to ensure
  // VS Code can match "Container.member" against the displayed name.
  private def searchViaIntelliJ(project: com.intellij.openapi.project.Project, query: String, cancelled: java.util.concurrent.atomic.AtomicBoolean): Seq[SymbolInformation] =
    val results = searchModels(project, query, cancelled)

    // VS Code fuzzy-matches query against symbol name. For dotted queries like
    // "Container.member", include the container in the name so VS Code can match.
    if query.contains('.') then
      results.map: sym =>
        val container = sym.getContainerName
        if container != null then
          val containerSimple = container.substring(container.lastIndexOf('.') + 1)
          SymbolInformation(s"$containerSimple.${sym.getName}", sym.getKind, sym.getLocation, container)
        else sym
    else results

  private def searchModels(project: com.intellij.openapi.project.Project, query: String, cancelled: java.util.concurrent.atomic.AtomicBoolean): Seq[SymbolInformation] =
    val results = ArrayBuffer[SymbolInformation]()
    val seen = new java.util.concurrent.ConcurrentHashMap[String, String]()
    try
      val symbolModel = GotoSymbolModel2(project, project)
      val classModel = GotoClassModel2(project)
      val provider = DefaultChooseByNameItemProvider(null)

      var processedCount = 0
      val processor: com.intellij.util.Processor[Any] = (element => {
        processedCount += 1
        if cancelled.get() || results.size >= MaxResults then false
        else
          element match
            case nav: NavigationItem =>
              val psi = nav match
                case p: PsiElement => Some(PsiUtils.unwrapSyntheticElement(p))
                case _ => None
              psi.foreach:
                case named: PsiNamedElement if isSignificantElement(named) =>
                  val rawName = named.getName
                  if rawName != null then
                    // Strip JVM companion class suffix (e.g., MyService$ → MyService)
                    val symbolName = if rawName.endsWith("$") then rawName.stripSuffix("$") else rawName
                    val containerName = getContainerName(named)
                    val qualKey = Option(containerName).filter(_.nonEmpty)
                      .map(c => s"$c.$symbolName").getOrElse(symbolName)
                      .stripSuffix("$")
                    if seen.putIfAbsent(qualKey, "") == null then
                      PsiUtils.elementToLocation(named).foreach: loc =>
                        val kind = PsiUtils.getSymbolKind(named)
                        results += new SymbolInformation(symbolName, kind, loc, containerName)
                case _ => ()
            case _ => ()
          true
      })

      for (model, label) <- Seq((classModel, "class"), (symbolModel, "symbol")) if !cancelled.get() && results.size < MaxResults do
        val indicator = createIndicator()
        try
          provider.filterElements(makeViewModel(project, model), query, true, indicator, processor)
        catch
          case _: com.intellij.openapi.progress.ProcessCanceledException => ()
          case e: Exception =>
            System.err.println(s"[SymbolProvider] $label model error for '$query': ${e.getClass.getName}: ${e.getMessage}")
      if results.nonEmpty then
        System.err.println(s"[SymbolProvider] '$query': processed=$processedCount results=${results.size}: ${results.map(s => s"${s.getName}(${s.getContainerName})@${s.getLocation.getUri}:${s.getLocation.getRange.getStart.getLine}").mkString(", ")}")
      else
        System.err.println(s"[SymbolProvider] '$query': processed=$processedCount results=0")
      results.toSeq
    catch
      case _: com.intellij.openapi.progress.ProcessCanceledException => results.toSeq
      case e: Throwable =>
        System.err.println(s"[SymbolProvider] Error for '$query': ${e.getClass.getName}: ${e.getMessage}")
        e.printStackTrace(System.err)
        results.toSeq

  private def makeViewModel(project: com.intellij.openapi.project.Project, model: ChooseByNameModel) = new ChooseByNameViewModel:
    override def getProject: com.intellij.openapi.project.Project = project
    override def getModel: ChooseByNameModel = model
    override def isSearchInAnyPlace: Boolean = true
    override def transformPattern(pattern: String): String = pattern
    override def canShowListForEmptyPattern: Boolean = false
    override def getMaximumListSizeLimit: Int = MaxResults

  /** Create a ProgressIndicator that is already started and running. */
  private def createIndicator(): com.intellij.openapi.progress.util.ProgressIndicatorBase =
    val indicator = com.intellij.openapi.progress.util.ProgressIndicatorBase()
    indicator.start()
    indicator

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
