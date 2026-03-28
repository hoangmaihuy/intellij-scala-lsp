package org.jetbrains.scalalsP.intellij

import com.intellij.find.findUsages.{FindUsagesHandlerBase, FindUsagesHandlerFactory, FindUsagesOptions}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.impl.rules.{UsageType, UsageTypeProvider}
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/references by delegating to IntelliJ's FindUsagesHandler,
 * which handles structural search, text occurrences, secondary elements (companions),
 * SAM types, overriding members, and compiler indices.
 */
class ReferencesProvider(projectManager: IntellijProjectManager):

  private val ResultLimit = 500

  @volatile var lastResultsWithTypes: Seq[ReferenceResult] = Seq.empty

  def getLastResultsWithTypes: Seq[ReferenceResult] = lastResultsWithTypes

  def findReferences(uri: String, position: Position, includeDeclaration: Boolean): Seq[Location] =
    val results = findReferencesWithTypes(uri, position, includeDeclaration)
    results.map(_.location)

  def findReferencesWithTypes(uri: String, position: Position, includeDeclaration: Boolean): Seq[ReferenceResult] =
    val results = projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findRefsAtOffset(psiFile, offset, includeDeclaration, uri)

      result.getOrElse(Seq.empty)

    lastResultsWithTypes = results
    results

  private def findRefsAtOffset(
    psiFile: PsiFile,
    offset: Int,
    includeDeclaration: Boolean,
    uri: String,
  ): Seq[ReferenceResult] =
    // Multi-resolve: get all possible declaration targets
    val targetElements = PsiUtils.resolveToDeclarations(psiFile, offset)

    if targetElements.isEmpty then return Seq.empty

    // For cached source files, map to real library elements
    val effectiveTargets = targetElements.flatMap: target =>
      if PsiUtils.isCachedSourceFile(uri) then
        PsiUtils.resolveLibraryElement(target).orElse(Some(target))
      else
        Some(target)

    if effectiveTargets.isEmpty then return Seq.empty

    val project = projectManager.getProject
    val scope = GlobalSearchScope.projectScope(project)

    val resultsBuffer = scala.collection.mutable.LinkedHashMap[(String, Int, Int), ReferenceResult]()

    for target <- effectiveTargets do
      val targetName = target match
        case n: PsiNamedElement => n.getName
        case _ => "?"
      System.err.println(s"[References] Target: ${target.getClass.getSimpleName} '$targetName'")

      val handler = findHandler(target)
      handler match
        case Some(h) =>
          processViaHandler(h, target, scope, resultsBuffer)
        case None =>
          processViaReferencesSearch(target, scope, resultsBuffer)

    // Add declarations if requested
    if includeDeclaration then
      for target <- effectiveTargets do
        PsiUtils.elementToLocation(target).foreach: loc =>
          val key = (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
          if !resultsBuffer.contains(key) then
            resultsBuffer(key) = ReferenceResult(loc, "Declaration")

    val allResults = resultsBuffer.values.toSeq
    if allResults.size > ResultLimit then
      System.err.println(s"[References] WARNING: Result count ${allResults.size} exceeds limit $ResultLimit, truncating")
      allResults.take(ResultLimit)
    else
      System.err.println(s"[References] Found ${allResults.size} references")
      allResults

  /** Find a FindUsagesHandler for the target via registered factories. */
  private def findHandler(target: PsiElement): Option[FindUsagesHandlerBase] =
    val project = projectManager.getProject
    try
      val factories = FindUsagesHandlerFactory.EP_NAME.getExtensionList(project).asScala
      factories.iterator.flatMap: factory =>
        try
          if factory.canFindUsages(target) then
            Option(factory.createFindUsagesHandler(target, false))
          else None
        catch case e: Exception =>
          System.err.println(s"[References] Factory ${factory.getClass.getSimpleName} failed: ${e.getMessage}")
          None
      .nextOption()
    catch case e: Exception =>
      System.err.println(s"[References] Failed to load FindUsagesHandlerFactory extensions: ${e.getMessage}")
      None

  /** Delegate to FindUsagesHandler.processElementUsages for comprehensive search. */
  private def processViaHandler(
    handler: FindUsagesHandlerBase,
    target: PsiElement,
    scope: GlobalSearchScope,
    resultsBuffer: scala.collection.mutable.LinkedHashMap[(String, Int, Int), ReferenceResult],
  ): Unit =
    val options = new FindUsagesOptions(projectManager.getProject)
    options.isUsages = true
    options.isSearchForTextOccurrences = true
    options.searchScope = scope

    try
      val processor: com.intellij.util.Processor[com.intellij.usageView.UsageInfo] =
        (usageInfo: com.intellij.usageView.UsageInfo) =>
          val element = usageInfo.getElement
          if element != null then
            PsiUtils.elementToLocation(element).foreach: loc =>
              val key = (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
              if !resultsBuffer.contains(key) then
                val usageType = classifyUsage(usageInfo)
                resultsBuffer(key) = ReferenceResult(loc, usageType)
          true

      // processElementUsages searches primary + secondary elements internally
      handler.processElementUsages(target, processor, options)
    catch
      case e: Exception =>
        System.err.println(s"[References] processElementUsages failed: ${e.getClass.getSimpleName}: ${e.getMessage}")

  /** Fallback: direct ReferencesSearch for non-Scala targets. */
  private def processViaReferencesSearch(
    target: PsiElement,
    scope: GlobalSearchScope,
    resultsBuffer: scala.collection.mutable.LinkedHashMap[(String, Int, Int), ReferenceResult],
  ): Unit =
    try
      ReferencesSearch.search(target, scope, false).forEach: (ref: PsiReference) =>
        val element = ref.getElement
        PsiUtils.elementToLocation(element).foreach: loc =>
          val key = (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
          if !resultsBuffer.contains(key) then
            resultsBuffer(key) = ReferenceResult(loc, "Unclassified")
        true
    catch
      case e: Exception =>
        System.err.println(s"[References] ReferencesSearch fallback failed: ${e.getClass.getSimpleName}: ${e.getMessage}")

  /** Classify a UsageInfo using IntelliJ's UsageTypeProvider extensions. */
  private def classifyUsage(usageInfo: com.intellij.usageView.UsageInfo): String =
    try
      val providers = UsageTypeProvider.EP_NAME.getExtensionList.asScala
      val element = usageInfo.getElement
      if element != null then
        providers.iterator.flatMap: provider =>
          Option(provider.getUsageType(element))
        .nextOption() match
          case Some(ut) => ut.toString
          case None => UsageType.UNCLASSIFIED.toString
      else
        UsageType.UNCLASSIFIED.toString
    catch
      case _: Exception => "Unclassified"
