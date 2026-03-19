package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{Position, PrepareRenameResult, Range, TextEdit, WorkspaceEdit}

import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/prepareRename and textDocument/rename.
 * Uses ReferencesSearch to find all usages, returns a WorkspaceEdit
 * without mutating PSI — the client applies edits.
 */
class RenameProvider(projectManager: IntellijProjectManager):

  def prepareRename(uri: String, position: Position): PrepareRenameResult | Null =
    ReadAction.compute[PrepareRenameResult | Null, RuntimeException]: () =>
      val named = findNamedElementAt(uri, position)
      named match
        case Some(element) =>
          val name = element.getName
          if name == null || name.isEmpty then null
          else
            // Return the range at the cursor position (the reference/usage site),
            // not the declaration — the client highlights this range in the editor
            (for
              vf <- projectManager.findVirtualFile(uri)
              document <- Option(FileDocumentManager.getInstance().getDocument(vf))
              psiFile <- projectManager.findPsiFile(uri)
            yield
              val offset = PsiUtils.positionToOffset(document, position)
              val leafAtCursor = PsiUtils.findReferenceElementAt(psiFile, offset)
              leafAtCursor match
                case Some(leaf) =>
                  val range = PsiUtils.nameElementToRange(document, leaf)
                  PrepareRenameResult(range, name)
                case None => null
            ).getOrElse(null)
        case None => null

  def rename(uri: String, position: Position, newName: String): WorkspaceEdit | Null =
    ReadAction.compute[WorkspaceEdit | Null, RuntimeException]: () =>
      val named = findNamedElementAt(uri, position)
      named match
        case Some(target) =>
          val project = projectManager.getProject
          val scope = GlobalSearchScope.projectScope(project)

          // Collect all reference locations
          val refEdits = ReferencesSearch.search(target, scope, false)
            .findAll()
            .asScala
            .flatMap: ref =>
              val refElement = ref.getElement
              for
                file <- Option(refElement.getContainingFile)
                vf <- Option(file.getVirtualFile)
                document <- Option(FileDocumentManager.getInstance().getDocument(vf))
              yield
                val refUri = PsiUtils.vfToUri(vf)
                val range = PsiUtils.nameElementToRange(document, refElement)
                (refUri, TextEdit(range, newName))
            .toSeq

          // Add the declaration itself
          val declEdits = (for
            file <- Option(target.getContainingFile)
            vf <- Option(file.getVirtualFile)
            document <- Option(FileDocumentManager.getInstance().getDocument(vf))
          yield
            val declUri = PsiUtils.vfToUri(vf)
            val range = PsiUtils.nameElementToRange(document, target)
            (declUri, TextEdit(range, newName))
          ).toSeq

          val allEdits = (declEdits ++ refEdits)
            .groupBy(_._1)
            .map((fileUri, edits) => fileUri -> edits.map(_._2).asJava)
            .asJava

          if allEdits.isEmpty then null
          else WorkspaceEdit(allEdits)

        case None => null

  private def findNamedElementAt(uri: String, position: Position): Option[PsiNamedElement] =
    val nested: Option[Option[PsiNamedElement]] =
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        resolveToNamedElement(psiFile, offset)
    nested.flatten

  private def resolveToNamedElement(psiFile: com.intellij.psi.PsiFile, offset: Int): Option[PsiNamedElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: element =>
      val ref = element.getReference
      if ref != null then
        Option(ref.resolve()).collect { case named: PsiNamedElement => named }
      else
        // No reference — we may be on a declaration.
        // Only return a named element if the cursor is on its name identifier.
        findNamedParentAtOffset(element, offset)

  private def findNamedParentAtOffset(element: PsiElement, offset: Int): Option[PsiNamedElement] =
    var current: PsiElement = element
    while current != null do
      current match
        case owner: PsiNameIdentifierOwner =>
          val nameId = owner.getNameIdentifier
          if nameId != null then
            val nameRange = nameId.getTextRange
            if nameRange.containsOffset(offset) then return Some(owner)
          // Name identifier doesn't cover cursor — keep walking up
        case _ => ()
      current = current.getParent
    None
