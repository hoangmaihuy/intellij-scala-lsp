package org.jetbrains.scalalsP.integration

import com.intellij.navigation.{ChooseByNameContributor, ChooseByNameContributorEx, NavigationItem}
import com.intellij.psi.{PsiElement, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FindSymbolParameters
import org.jetbrains.scalalsP.intellij.{PsiUtils, ScalaTypes, SymbolProvider}
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*
import scala.collection.mutable

/**
 * Tests each step of the workspace/symbol pipeline for external library elements.
 * Reproduces the exact code path from SymbolProvider.searchViaContributors to
 * pinpoint where library elements get dropped.
 */
class ExternalSymbolPipelineTest extends ExternalLibraryTestBase:

  private def isSignificantElement(element: PsiNamedElement): Boolean =
    val name = element.getName
    if name == null || name.isEmpty then return false
    ScalaTypes.isTypeDefinition(element) || ScalaTypes.isFunction(element) ||
    ScalaTypes.isValue(element) || ScalaTypes.isVariable(element) ||
    ScalaTypes.isTypeAlias(element) || ScalaTypes.isPackaging(element) ||
    element.isInstanceOf[com.intellij.psi.PsiClass] || element.isInstanceOf[com.intellij.psi.PsiMethod]

  /** Step 1: processNames finds matching names */
  def testProcessNamesFindsMonad(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val project = projectManager.getProject
    val scope = GlobalSearchScope.allScope(project)
    val contributors =
      ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala ++
      ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList.asScala

    val allNames = mutable.ArrayBuffer[String]()
    for contributor <- contributors do
      contributor match
        case ex: ChooseByNameContributorEx =>
          ex.processNames(
            ((name: String) => {
              if name != null && name.toLowerCase.contains("monad") then
                allNames += name
              true
            }): com.intellij.util.Processor[String],
            scope, null
          )
        case _ => ()

    assertTrue(s"processNames should find 'Monad', found ${allNames.size} names: ${allNames.take(10).mkString(", ")}",
      allNames.exists(_.contains("Monad")))

  /** Step 2: processElementsWithName returns PsiElements for "Monad" */
  def testProcessElementsWithNameReturnsElements(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val project = projectManager.getProject
    val scope = GlobalSearchScope.allScope(project)
    val contributors =
      ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala

    val elements = mutable.ArrayBuffer[(String, String)]() // (name, className)
    val params = FindSymbolParameters.wrap("Monad", project, true)
    for contributor <- contributors do
      contributor match
        case ex: ChooseByNameContributorEx =>
          ex.processElementsWithName(
            "Monad",
            ((item: NavigationItem) => {
              item match
                case psi: PsiElement =>
                  elements += ((psi.getClass.getSimpleName, Option(psi match {
                    case n: PsiNamedElement => n.getName
                    case _ => "?"
                  }).getOrElse("?")))
                case _ => ()
              true
            }): com.intellij.util.Processor[NavigationItem],
            params
          )
        case _ => ()

    assertTrue(s"processElementsWithName('Monad') should return elements, got ${elements.size}: ${elements.take(5).mkString(", ")}",
      elements.nonEmpty)

  /** Step 3: Elements pass isSignificantElement check */
  def testLibraryElementsAreSignificant(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val project = projectManager.getProject
    val scope = GlobalSearchScope.allScope(project)
    val contributors =
      ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala

    val significant = mutable.ArrayBuffer[String]()
    val notSignificant = mutable.ArrayBuffer[String]()
    val params = FindSymbolParameters.wrap("Monad", project, true)

    for contributor <- contributors do
      contributor match
        case ex: ChooseByNameContributorEx =>
          ex.processElementsWithName(
            "Monad",
            ((item: NavigationItem) => {
              item match
                case psi: PsiNamedElement =>
                  if isSignificantElement(psi) then
                    significant += s"${psi.getName} (${psi.getClass.getSimpleName})"
                  else
                    notSignificant += s"${psi.getName} (${psi.getClass.getSimpleName})"
                case _ => ()
              true
            }): com.intellij.util.Processor[NavigationItem],
            params
          )
        case _ => ()

    System.err.println(s"[Pipeline] Significant: ${significant.take(5).mkString(", ")}")
    System.err.println(s"[Pipeline] NOT significant: ${notSignificant.take(5).mkString(", ")}")
    assertTrue(s"Some 'Monad' elements should be significant. Significant: ${significant.size}, Not: ${notSignificant.size}. Not significant: ${notSignificant.take(5).mkString(", ")}",
      significant.nonEmpty)

  /** Step 4: elementToLocation succeeds for library elements */
  def testElementToLocationWorksForLibraryElements(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val project = projectManager.getProject
    val scope = GlobalSearchScope.allScope(project)
    val contributors =
      ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala

    val succeeded = mutable.ArrayBuffer[String]()
    val failed = mutable.ArrayBuffer[String]()
    val params = FindSymbolParameters.wrap("Monad", project, true)

    for contributor <- contributors do
      contributor match
        case ex: ChooseByNameContributorEx =>
          ex.processElementsWithName(
            "Monad",
            ((item: NavigationItem) => {
              item match
                case psi: PsiNamedElement if isSignificantElement(psi) =>
                  val loc = PsiUtils.elementToLocation(psi)
                  if loc.isDefined then
                    succeeded += s"${psi.getName} -> ${loc.get.getUri}"
                  else
                    // Log details about WHY it failed
                    val file = Option(psi.getContainingFile)
                    val vf = file.flatMap(f => Option(f.getVirtualFile))
                    val vfPath = vf.map(_.getPath).getOrElse("NO_VF")
                    val hasDoc = vf.exists(v => com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(v) != null)
                    failed += s"${psi.getName} (${psi.getClass.getSimpleName}) vf=$vfPath hasDoc=$hasDoc"
                case _ => ()
              true
            }): com.intellij.util.Processor[NavigationItem],
            params
          )
        case _ => ()

    System.err.println(s"[Pipeline] elementToLocation succeeded: ${succeeded.size}")
    succeeded.take(3).foreach(s => System.err.println(s"[Pipeline]   OK: $s"))
    System.err.println(s"[Pipeline] elementToLocation FAILED: ${failed.size}")
    failed.take(5).foreach(s => System.err.println(s"[Pipeline]   FAIL: $s"))

    assertTrue(s"elementToLocation should succeed for some Monad elements. Succeeded: ${succeeded.size}, Failed: ${failed.size}. Failures: ${failed.take(3).mkString("; ")}",
      succeeded.nonEmpty)

  /** Step 5: Full pipeline — workspaceSymbols returns results */
  def testFullPipelineReturnsResults(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = SymbolProvider(projectManager).workspaceSymbols("Monad")
    assertTrue(s"workspaceSymbols('Monad') should return results, got ${result.size}",
      result.nonEmpty)
    val monad = result.find(_.getName == "Monad")
    assertTrue("Should find exact 'Monad' symbol", monad.isDefined)

  /** Same pipeline for ZIO */
  def testFullPipelineZIO(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = SymbolProvider(projectManager).workspaceSymbols("ZIO")
    assertTrue(s"workspaceSymbols('ZIO') should return results, got ${result.size}",
      result.nonEmpty)
    val zio = result.find(_.getName == "ZIO")
    assertTrue("Should find exact 'ZIO' symbol", zio.isDefined)
