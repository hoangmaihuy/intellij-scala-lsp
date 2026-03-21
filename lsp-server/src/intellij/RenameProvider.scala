package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiMethod, PsiNameIdentifierOwner, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.{DefinitionsScopedSearch, ReferencesSearch}
import org.eclipse.lsp4j.{Position, PrepareRenameResult, Range, RenameFile, ResourceOperation, TextDocumentEdit, TextEdit, VersionedTextDocumentIdentifier, WorkspaceEdit}
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither}
import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/prepareRename and textDocument/rename.
 * Uses ReferencesSearch to find all usages, returns a WorkspaceEdit
 * without mutating PSI — the client applies edits.
 */
class RenameProvider(projectManager: IntellijProjectManager):

  private val forbiddenNames = Set("equals", "hashCode", "toString", "unapply", "apply", "unary_!")

  def prepareRename(uri: String, position: Position): PrepareRenameResult | Null =
    projectManager.smartReadAction: () =>
      val named = findNamedElementAt(uri, position)
      named match
        case Some(element) =>
          val name = element.getName
          if name == null || name.isEmpty then null
          else if forbiddenNames.contains(name) then null
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
    if newName == null || newName.isBlank then return null
    projectManager.smartReadAction: () =>
      val named = findNamedElementAt(uri, position)
      named match
        case Some(target) =>
          val project = projectManager.getProject
          val scope = GlobalSearchScope.projectScope(project)

          // Collect edits for a given named element (decl + all references)
          def collectEditsFor(elem: PsiNamedElement): Seq[(String, TextEdit)] =
            val refEdits = ReferencesSearch.search(elem, scope, false)
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

            val declEdits = (for
              file <- Option(elem.getContainingFile)
              vf <- Option(file.getVirtualFile)
              document <- Option(FileDocumentManager.getInstance().getDocument(vf))
            yield
              val declUri = PsiUtils.vfToUri(vf)
              val range = PsiUtils.nameElementToRange(document, elem)
              (declUri, TextEdit(range, newName))
            ).toSeq

            (declEdits ++ refEdits)
              .distinctBy((u, edit) => (u, edit.getRange.getStart.getLine, edit.getRange.getStart.getCharacter, edit.getRange.getEnd.getLine, edit.getRange.getEnd.getCharacter))

          // Collect all edits: main target + companion (if any) + abstract implementations
          var allEdits = collectEditsFor(target)

          // Companion object/class pairing
          // Use Class.forName to avoid bytecode constant pool references to Scala plugin types,
          // which would cause NoClassDefFoundError before IntelliJ's plugin classloader is ready
          try
            val scTypeDefClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition")
            if scTypeDefClass.isInstance(target) then
              val utilClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil")
              val method = utilClass.getMethod("getCompanionModule", scTypeDefClass)
              method.invoke(null, target).asInstanceOf[Option[?]].foreach:
                case companion: PsiNamedElement => allEdits = allEdits ++ collectEditsFor(companion)
                case _ => ()
          catch case _: Exception => ()

          // Abstract method implementations
          target match
            case method: PsiMethod if method.hasModifierProperty("abstract") =>
              DefinitionsScopedSearch.search(method, scope).findAll().asScala.foreach: impl =>
                impl match
                  case named: PsiNamedElement => allEdits = allEdits ++ collectEditsFor(named)
                  case _ => ()
            case _ => ()

          // Deduplicate across all sources
          allEdits = allEdits
            .distinctBy((u, edit) => (u, edit.getRange.getStart.getLine, edit.getRange.getStart.getCharacter, edit.getRange.getEnd.getLine, edit.getRange.getEnd.getCharacter))

          if allEdits.isEmpty then null
          else
            // Check if we need a file rename resource operation
            val isScalaTypeDef = try
              Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition").isInstance(target)
            catch case _: Exception => false

            val fileRenameOp = if isScalaTypeDef then
              val named = target.asInstanceOf[PsiNamedElement]
              for
                containingFile <- Option(named.getContainingFile)
                vf <- Option(containingFile.getVirtualFile)
                fileName = vf.getNameWithoutExtension
                if fileName == named.getName
                oldFileUri = PsiUtils.vfToUri(vf)
                newFileUri = oldFileUri.substring(0, oldFileUri.lastIndexOf('/') + 1) + newName + ".scala"
              yield (oldFileUri, newFileUri)
            else None

            fileRenameOp match
              case Some((oldUri, newUri)) =>
                // Use documentChanges (supports ResourceOperation) instead of changes map
                val editsByUri = allEdits.groupBy(_._1).map((fileUri, edits) =>
                  fileUri -> edits.map(_._2)
                )
                val docEdits = editsByUri.map: (fileUri, edits) =>
                  val versionedId = VersionedTextDocumentIdentifier(fileUri, null)
                  LspEither.forLeft[TextDocumentEdit, ResourceOperation](
                    TextDocumentEdit(versionedId, edits.asJava)
                  )
                val renameOp = LspEither.forRight[TextDocumentEdit, ResourceOperation](
                  RenameFile(oldUri, newUri)
                )
                val documentChanges = (docEdits.toSeq :+ renameOp).asJava
                WorkspaceEdit(documentChanges)

              case None =>
                val changesMap = allEdits
                  .groupBy(_._1)
                  .map((fileUri, edits) => fileUri -> edits.map(_._2).asJava)
                  .asJava
                WorkspaceEdit(changesMap)

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
