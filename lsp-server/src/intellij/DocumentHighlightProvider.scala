package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner, PsiNamedElement}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{DocumentHighlight, DocumentHighlightKind, Position}

import scala.jdk.CollectionConverters.*

class DocumentHighlightProvider(projectManager: IntellijProjectManager):

  def getDocumentHighlights(uri: String, position: Position): Seq[DocumentHighlight] =
    projectManager.smartReadAction: () =>
      (for
        psiFile <- projectManager.findPsiFile(uri)
        vf      <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findTarget(psiFile, offset).map: named =>
          val scope  = LocalSearchScope(psiFile)
          val refs   = ReferencesSearch.search(named, scope, false).findAll().asScala
          val highlights = scala.collection.mutable.ArrayBuffer[DocumentHighlight]()

          // Definition = Write
          if named.getContainingFile == psiFile then
            highlights += DocumentHighlight(
              PsiUtils.nameElementToRange(document, named),
              DocumentHighlightKind.Write
            )

          // References = Read
          for ref <- refs do
            val refElem = ref.getElement
            if refElem.getContainingFile == psiFile then
              highlights += DocumentHighlight(
                PsiUtils.nameElementToRange(document, refElem),
                DocumentHighlightKind.Read
              )

          highlights.toSeq
        .getOrElse(Seq.empty)
      ).getOrElse(Seq.empty)

  private def findTarget(psiFile: com.intellij.psi.PsiFile, offset: Int): Option[PsiNamedElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: elem =>
      val ref = elem.getReference
      if ref != null then
        Option(ref.resolve()).collect { case n: PsiNamedElement => n }
      else
        // Walk up to find a declaration whose name identifier covers this offset
        var current: PsiElement = elem
        var found: Option[PsiNamedElement] = None
        while current != null && found.isEmpty do
          current match
            case owner: PsiNameIdentifierOwner =>
              val nameId = owner.getNameIdentifier
              if nameId != null && nameId.getTextRange.contains(elem.getTextRange) then
                found = Some(owner)
            case _ => ()
          current = current.getParent
        found
