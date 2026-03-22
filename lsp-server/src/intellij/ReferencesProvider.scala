package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
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
        findRefsAtOffset(psiFile, offset, includeDeclaration, uri)

      result.getOrElse(Seq.empty)

  private def findRefsAtOffset(
    psiFile: PsiFile,
    offset: Int,
    includeDeclaration: Boolean,
    uri: String,
  ): Seq[Location] =
    val targetElement = PsiUtils.resolveToDeclaration(psiFile, offset)

    // For cached source files (external deps), the PsiElement is from a standalone file,
    // not connected to IntelliJ's index. Map to the real library element for search.
    val effectiveTarget = targetElement.flatMap: target =>
      if PsiUtils.isCachedSourceFile(uri) then
        PsiUtils.resolveLibraryElement(target).orElse(Some(target))
      else
        Some(target)

    effectiveTarget match
      case Some(target) =>
        System.err.println(s"[References] Searching references for: ${target.getClass.getName} '${target match { case n: PsiNamedElement => n.getName; case _ => "?" }}'")
        val project = projectManager.getProject
        val scope = GlobalSearchScope.projectScope(project)

        val refsBuffer = scala.collection.mutable.ArrayBuffer[Location]()
        try
          ReferencesSearch.search(target, scope, false).forEach: (ref: PsiReference) =>
            PsiUtils.elementToLocation(ref.getElement).foreach(refsBuffer += _)
            true
        catch
          case e: Exception =>
            System.err.println(s"[References] ReferencesSearch partially failed: ${e.getClass.getSimpleName}: ${e.getMessage}")

        System.err.println(s"[References] Found ${refsBuffer.size} references")
        val references = refsBuffer.toSeq
        if includeDeclaration then
          val declLocation = PsiUtils.elementToLocation(target).toSeq
          (declLocation ++ references).distinct
        else
          references

      case None =>
        Seq.empty

