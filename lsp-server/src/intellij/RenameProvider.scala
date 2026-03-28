package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessorBase
import org.eclipse.lsp4j.{Position, PrepareRenameResult, RenameFile, ResourceOperation, TextDocumentEdit, TextEdit, VersionedTextDocumentIdentifier, WorkspaceEdit}
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither}
import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/prepareRename and textDocument/rename.
 * Delegates to IntelliJ's RenamePsiElementProcessor extension point
 * (which includes Scala plugin's rename processors for methods, classes,
 * variables, binding patterns, etc.) for finding all references to rename.
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

    // Phase 1: Resolve target in read action
    val target = projectManager.smartReadAction(() => findNamedElementAt(uri, position))
    if target.isEmpty then return null
    val targetElem = target.get

    // Phase 2: Use RenamePsiElementProcessor.prepareRenaming to discover related elements.
    // Some processors need read action, others call invokeAndWait (needs no read action).
    // Try with read action first, fall back to without.
    val processor = RenamePsiElementProcessorBase.forPsiElement(targetElem)
    val allRenames = new java.util.LinkedHashMap[PsiElement, String]()
    allRenames.put(targetElem, newName)
    try
      com.intellij.openapi.application.ReadAction.run[Exception]: () =>
        processor.prepareRenaming(targetElem, newName, allRenames)
    catch case _: Exception =>
      try
        processor.prepareRenaming(targetElem, newName, allRenames)
      catch case e2: Exception =>
        System.err.println(s"[Rename] prepareRenaming failed: ${e2.getMessage}")
    System.err.println(s"[Rename] ${processor.getClass.getSimpleName} prepared ${allRenames.size()} elements to rename")

    // Phase 3: Collect edits in read action
    projectManager.smartReadAction: () =>
      val project = projectManager.getProject
      val scope = GlobalSearchScope.projectScope(project)

      var allEdits = Seq.empty[(String, TextEdit)]
      allRenames.asScala.foreach: (elem, elemNewName) =>
        elem match
          case named: PsiNamedElement =>
            allEdits = allEdits ++ collectEditsFor(named, elemNewName, processor, scope)
          case _ => ()

      // Deduplicate
      allEdits = allEdits
        .distinctBy((u, edit) => (u, edit.getRange.getStart.getLine, edit.getRange.getStart.getCharacter,
          edit.getRange.getEnd.getLine, edit.getRange.getEnd.getCharacter))

      if allEdits.isEmpty then null
      else buildWorkspaceEdit(targetElem, newName, allEdits)

  /** Collect text edits for a single element using RenamePsiElementProcessor.findReferences. */
  private def collectEditsFor(
    elem: PsiNamedElement, newName: String,
    processor: RenamePsiElementProcessorBase, scope: com.intellij.psi.search.SearchScope
  ): Seq[(String, TextEdit)] =
    // Use the processor's findReferences which handles Scala-specific reference discovery
    val refs = processor.findReferences(elem, scope, false)
    val refEdits = refs.asScala.flatMap: ref =>
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
      .distinctBy((u, edit) => (u, edit.getRange.getStart.getLine, edit.getRange.getStart.getCharacter,
        edit.getRange.getEnd.getLine, edit.getRange.getEnd.getCharacter))

  private def buildWorkspaceEdit(target: PsiNamedElement, newName: String, allEdits: Seq[(String, TextEdit)]): WorkspaceEdit =
    // Check if we need a file rename resource operation
    val fileRenameOp = if ScalaTypes.isTypeDefinition(target) then
      for
        containingFile <- Option(target.getContainingFile)
        vf <- Option(containingFile.getVirtualFile)
        fileName = vf.getNameWithoutExtension
        if fileName == target.getName
        oldFileUri = PsiUtils.vfToUri(vf)
        newFileUri = oldFileUri.substring(0, oldFileUri.lastIndexOf('/') + 1) + newName + ".scala"
      yield (oldFileUri, newFileUri)
    else None

    fileRenameOp match
      case Some((oldUri, newUri)) =>
        val editsByUri = allEdits.groupBy(_._1).map((fileUri, edits) => fileUri -> edits.map(_._2))
        val docEdits = editsByUri.map: (fileUri, edits) =>
          val versionedId = VersionedTextDocumentIdentifier(fileUri, null)
          LspEither.forLeft[TextDocumentEdit, ResourceOperation](
            TextDocumentEdit(versionedId, edits.asJava)
          )
        val renameOp = LspEither.forRight[TextDocumentEdit, ResourceOperation](
          RenameFile(oldUri, newUri)
        )
        WorkspaceEdit((docEdits.toSeq :+ renameOp).asJava)
      case None =>
        val changesMap = allEdits
          .groupBy(_._1)
          .map((fileUri, edits) => fileUri -> edits.map(_._2).asJava)
          .asJava
        WorkspaceEdit(changesMap)

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
