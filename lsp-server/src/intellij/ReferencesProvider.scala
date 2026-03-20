package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/references using IntelliJ's ReferencesSearch.
 */
class ReferencesProvider(projectManager: IntellijProjectManager):

  def findReferences(uri: String, position: Position, includeDeclaration: Boolean): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findRefsAtOffset(psiFile, offset, includeDeclaration)

      result.getOrElse(Seq.empty)

  private def findRefsAtOffset(
    psiFile: com.intellij.psi.PsiFile,
    offset: Int,
    includeDeclaration: Boolean
  ): Seq[Location] =
    val targetElement = PsiUtils.resolveToDeclaration(psiFile, offset)

    targetElement match
      case Some(target) =>
        val project = projectManager.getProject
        val scope = GlobalSearchScope.projectScope(project)

        // Use forEach instead of findAll to collect partial results if search crashes partway
        val refsBuffer = scala.collection.mutable.ArrayBuffer[Location]()
        try
          ReferencesSearch.search(target, scope, false).forEach: (ref: com.intellij.psi.PsiReference) =>
            PsiUtils.elementToLocation(ref.getElement).foreach(refsBuffer += _)
            true
        catch
          case e: Exception =>
            System.err.println(s"[References] ReferencesSearch partially failed: ${e.getClass.getSimpleName}: ${e.getMessage}")

        val references = refsBuffer.toSeq
        if includeDeclaration then
          val declLocation = PsiUtils.elementToLocation(target).toSeq
          (declLocation ++ references).distinct
        else
          references

      case None =>
        Seq.empty

