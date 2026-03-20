package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

// Implements textDocument/implementation.
// Finds all implementations/subclasses of a trait, class, or abstract method.
class ImplementationProvider(projectManager: IntellijProjectManager):

  def getImplementations(uri: String, position: Position): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findImplementationsAtOffset(psiFile, offset)

      result.getOrElse(Seq.empty)

  private def findImplementationsAtOffset(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    val targetElement = PsiUtils.resolveToDeclaration(psiFile, offset)

    targetElement match
      case Some(target) =>
        val project = projectManager.getProject
        val scope = GlobalSearchScope.allScope(project)

        try
          // First try DefinitionsScopedSearch on the target directly
          val directResults = DefinitionsScopedSearch.search(target, scope)
            .findAll().asScala.toSet

          // If no results and target is a method, try MethodImplementationsSearch via reflection
          // (PsiMethod is in the Java plugin which has a separate classloader in daemon mode)
          val results = if directResults.nonEmpty then directResults
          else searchMethodImplementationsReflective(target, scope)

          results
            .flatMap(impl => PsiUtils.elementToLocation(impl))
            .toSeq
        catch
          case e: Exception =>
            System.err.println(s"[ImplementationProvider] Error: ${e.getClass.getName}: ${e.getMessage}")
            Seq.empty

      case None =>
        Seq.empty

  /** Use reflection to call MethodImplementationsSearch.getOverridingMethods.
    * PsiMethod lives in the Java plugin which may be in a different classloader. */
  private def searchMethodImplementationsReflective(target: PsiElement, scope: GlobalSearchScope): Set[PsiElement] =
    try
      val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
      if !psiMethodClass.isInstance(target) then return Set.empty

      val methods = new java.util.ArrayList[AnyRef]()
      val searchClass = Class.forName("com.intellij.codeInsight.navigation.MethodImplementationsSearch")
      val getOverridingMethods = searchClass.getMethod("getOverridingMethods",
        psiMethodClass,
        classOf[java.util.List[_]],
        classOf[com.intellij.psi.search.SearchScope])
      getOverridingMethods.invoke(null, target, methods, scope)
      methods.asScala.collect { case e: PsiElement => e }.toSet
    catch
      case _: ClassNotFoundException => Set.empty
      case _: NoSuchMethodException => Set.empty
      case e: Exception =>
        System.err.println(s"[ImplementationProvider] MethodSearch error: ${e.getMessage}")
        Set.empty
