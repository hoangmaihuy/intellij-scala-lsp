package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
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
        val scope = GlobalSearchScope.projectScope(project)

        try
          val implementations = DefinitionsScopedSearch.search(target, scope)
            .findAll()
            .asScala
            .flatMap(impl => PsiUtils.elementToLocation(impl))
            .toSeq

          implementations
        catch
          case e: Exception =>
            System.err.println(s"[ImplementationProvider] Error: ${e.getMessage}")
            Seq.empty

      case None =>
        Seq.empty

