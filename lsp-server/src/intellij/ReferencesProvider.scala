package org.jetbrains.scalalsP.intellij

import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.search.{GlobalSearchScope, PsiNonJavaFileReferenceProcessor, PsiSearchHelper}
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/references using IntelliJ's ReferencesSearch,
 * with multi-resolve, allScope, secondary elements (companions/bean properties),
 * text occurrence search, and usage type classification.
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
    val scope = GlobalSearchScope.allScope(project)

    // Collect secondary elements (companions, bean properties) via ScalaFindUsagesHandlerFactory
    val allTargets = effectiveTargets ++ discoverSecondaryElements(effectiveTargets)
    val uniqueTargets = allTargets.distinctBy(t => (t.getClass.getName, t.getTextOffset, Option(t.getContainingFile).map(_.getVirtualFile)))

    System.err.println(s"[References] Searching references for ${uniqueTargets.size} target(s)")

    val resultsBuffer = scala.collection.mutable.LinkedHashMap[(String, Int, Int), ReferenceResult]()

    // Structural search for each target
    for target <- uniqueTargets do
      val targetName = target match
        case n: PsiNamedElement => n.getName
        case _ => "?"
      System.err.println(s"[References] Target: ${target.getClass.getSimpleName} '$targetName'")

      try
        ReferencesSearch.search(target, scope, false).forEach: (ref: PsiReference) =>
          val element = ref.getElement
          val unwrapped = PsiUtils.unwrapSyntheticElement(element)
          PsiUtils.elementToLocation(unwrapped).foreach: loc =>
            val key = (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
            if !resultsBuffer.contains(key) then
              val usageType = PsiUtils.getUsageType(unwrapped)
              resultsBuffer(key) = ReferenceResult(loc, usageType)
          true
      catch
        case e: Exception =>
          System.err.println(s"[References] ReferencesSearch partially failed: ${e.getClass.getSimpleName}: ${e.getMessage}")

    // Text occurrence search for named targets
    for target <- uniqueTargets do
      target match
        case named: PsiNamedElement =>
          val name = named.getName
          if name != null && name.nonEmpty then
            try
              val processor: PsiNonJavaFileReferenceProcessor =
                ((file: PsiFile, startOffset: Int, endOffset: Int) => {
                  val elementAtOffset = file.findElementAt(startOffset)
                  if elementAtOffset != null then
                    PsiUtils.elementToLocation(elementAtOffset).foreach: loc =>
                      val key = (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
                      if !resultsBuffer.contains(key) then
                        resultsBuffer(key) = ReferenceResult(loc, ReferenceResult.TextOccurrence)
                  true
                }): PsiNonJavaFileReferenceProcessor
              PsiSearchHelper.getInstance(project).processUsagesInNonJavaFiles(named, name, processor, scope)
            catch
              case e: Exception =>
                System.err.println(s"[References] Text search failed for '$name': ${e.getClass.getSimpleName}: ${e.getMessage}")
        case _ => // skip non-named elements

    // Add declarations if requested
    if includeDeclaration then
      for target <- uniqueTargets do
        PsiUtils.elementToLocation(target).foreach: loc =>
          val key = (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
          if !resultsBuffer.contains(key) then
            resultsBuffer(key) = ReferenceResult(loc, ReferenceResult.Read)

    val allResults = resultsBuffer.values.toSeq
    if allResults.size > ResultLimit then
      System.err.println(s"[References] WARNING: Result count ${allResults.size} exceeds limit $ResultLimit, truncating")
      allResults.take(ResultLimit)
    else
      System.err.println(s"[References] Found ${allResults.size} references")
      allResults

  /**
   * Discover secondary elements (companion objects, bean properties) via
   * ScalaFindUsagesHandlerFactory reflection.
   */
  private def discoverSecondaryElements(targets: Seq[PsiElement]): Seq[PsiElement] =
    val project = projectManager.getProject
    try
      val factories = FindUsagesHandlerFactory.EP_NAME.getExtensionList(project).asScala
      val scalaFactory = factories.find(_.getClass.getName.contains("ScalaFindUsagesHandlerFactory"))
      scalaFactory match
        case Some(factory) =>
          targets.flatMap: target =>
            try
              if factory.canFindUsages(target) then
                val handler = factory.createFindUsagesHandler(target, false)
                if handler != null then
                  handler.getSecondaryElements.toSeq
                else
                  Seq.empty
              else
                Seq.empty
            catch
              case e: Exception =>
                System.err.println(s"[References] Secondary element discovery failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
                Seq.empty
        case None =>
          System.err.println("[References] ScalaFindUsagesHandlerFactory not found")
          Seq.empty
    catch
      case e: Exception =>
        System.err.println(s"[References] Failed to load FindUsagesHandlerFactory extensions: ${e.getClass.getSimpleName}: ${e.getMessage}")
        Seq.empty
