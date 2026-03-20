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
    projectManager.smartReadAction: () =>
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
          // Try multiResolve for poly-variant references (check ref, not element)
          val polyRefOpt: Option[com.intellij.psi.PsiPolyVariantReference] = ref match
            case pr: com.intellij.psi.PsiPolyVariantReference => Some(pr)
            case _ => element match
              case pr: com.intellij.psi.PsiPolyVariantReference => Some(pr)
              case _ => None

          polyRefOpt match
            case Some(polyRef) =>
              // Try strict resolve first, then fallback to incomplete results
              val strict = polyRef.multiResolve(false)
              val results = if strict.nonEmpty then strict else polyRef.multiResolve(true)
              val locations = results
                .flatMap(rr => Option(rr.getElement))
                .flatMap(target => PsiUtils.elementToLocation(target))
                .toSeq
              // If multiResolve found elements but elementToLocation failed, try resolve() as fallback
              if locations.isEmpty then
                Option(ref.resolve())
                  .flatMap(target => PsiUtils.elementToLocation(target))
                  .toSeq
              else locations
            case None =>
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
