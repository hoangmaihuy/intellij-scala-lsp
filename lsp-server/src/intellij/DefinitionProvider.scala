package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Location, Position}

/**
 * Implements textDocument/definition by resolving references through IntelliJ's PSI.
 */
class DefinitionProvider(projectManager: IntellijProjectManager):

  def getDefinition(uri: String, position: Position): Seq[Location] =
    ReadAction.compute[Seq[Location], RuntimeException]: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        val locations = resolveAtOffset(psiFile, offset)
        locations

      result.getOrElse(Seq.empty)

  private def resolveAtOffset(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    PsiUtils.findReferenceElementAt(psiFile, offset) match
      case Some(element) =>
        val ref = element.getReference
        if ref != null then
          // Try multiResolve for Scala references first
          element match
            case polyRef: com.intellij.psi.PsiPolyVariantReference =>
              polyRef.multiResolve(false)
                .flatMap(rr => Option(rr.getElement))
                .flatMap(target => PsiUtils.elementToLocation(target))
                .toSeq
            case _ =>
              Option(ref.resolve())
                .flatMap(target => PsiUtils.elementToLocation(target))
                .toSeq
        else
          // Element itself might be a declaration; try to navigate to it
          navigateElement(element)
      case None =>
        Seq.empty

  private def navigateElement(element: PsiElement): Seq[Location] =
    // If the element is a named element, return its own location
    element match
      case named: com.intellij.psi.PsiNamedElement =>
        PsiUtils.elementToLocation(named).toSeq
      case _ =>
        Seq.empty
