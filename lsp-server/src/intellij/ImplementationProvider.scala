package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.navigation.MethodImplementationsSearch
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiMethod, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

// Implements textDocument/implementation.
// Finds all implementations/subclasses of a trait, class, or abstract method.
// Uses DefinitionsScopedSearch for types and MethodImplementationsSearch for methods.
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
          // For methods, use MethodImplementationsSearch which handles Scala overrides.
          // DefinitionsScopedSearch doesn't find overrides of abstract method declarations.
          val isMethod = target.isInstanceOf[PsiMethod] ||
            target.getClass.getName.contains("ScFunction")

          val implementations = if isMethod then
            // MethodImplementationsSearch finds overriding methods
            val methods = new java.util.ArrayList[PsiMethod]()
            target match
              case m: PsiMethod =>
                MethodImplementationsSearch.getOverridingMethods(m, methods, scope)
              case _ =>
                // Scala function that doesn't extend PsiMethod — try reflection
                try
                  val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
                  if psiMethodClass.isInstance(target) then
                    MethodImplementationsSearch.getOverridingMethods(
                      psiMethodClass.cast(target).asInstanceOf[PsiMethod], methods, scope)
                catch case _: Exception => ()
            methods.asScala.toSet
          else
            // For types (traits, classes), use DefinitionsScopedSearch
            DefinitionsScopedSearch.search(target, scope).findAll().asScala.toSet

          implementations
            .flatMap(impl => PsiUtils.elementToLocation(impl))
            .toSeq
        catch
          case e: Exception =>
            System.err.println(s"[ImplementationProvider] Error: ${e.getClass.getName}: ${e.getMessage}")
            Seq.empty

      case None =>
        Seq.empty

