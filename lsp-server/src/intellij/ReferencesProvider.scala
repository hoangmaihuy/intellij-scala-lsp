package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
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
    val targetElement = resolveToDeclaration(psiFile, offset)

    targetElement match
      case Some(target) =>
        val project = projectManager.getProject
        val scope = GlobalSearchScope.projectScope(project)

        val references = ReferencesSearch.search(target, scope, false)
          .findAll()
          .asScala
          .flatMap(ref => PsiUtils.elementToLocation(ref.getElement))
          .toSeq

        if includeDeclaration then
          val declLocation = PsiUtils.elementToLocation(target).toSeq
          (declLocation ++ references).distinct
        else
          references

      case None =>
        Seq.empty

  private def resolveToDeclaration(psiFile: com.intellij.psi.PsiFile, offset: Int): Option[PsiElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: element =>
      val ref = element.getReference
      if ref != null then
        Option(ref.resolve())
      else
        // Walk up to find the containing named element (class, trait, method, val, etc.)
        // This handles the case where the cursor is on the definition itself
        var parent = element.getParent
        while parent != null && !parent.isInstanceOf[com.intellij.psi.PsiNamedElement] do
          parent = parent.getParent
        if parent != null then Some(parent) else Some(element)
