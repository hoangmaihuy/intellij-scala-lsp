package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.highlighting.{HighlightUsagesHandler, HighlightUsagesHandlerBase, HighlightUsagesHandlerFactory}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.ProperTextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiNameIdentifierOwner, PsiNamedElement}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{DocumentHighlight, DocumentHighlightKind, Position}

import scala.jdk.CollectionConverters.*

class DocumentHighlightProvider(projectManager: IntellijProjectManager):

  def getDocumentHighlights(uri: String, position: Position): Seq[DocumentHighlight] =
    try
      val (psiFile, document) = projectManager.smartReadAction: () =>
        (for
          pf <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          doc <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield (pf, doc)).getOrElse(return Seq.empty)

      val offset = PsiUtils.positionToOffset(document, position)
      val project = psiFile.getProject

      // Try IntelliJ's HighlightUsagesHandlerFactory EP first (provides Scala-specific highlighting)
      var editor: Editor = null
      try
        val createEditor: Runnable = () =>
          editor = EditorFactory.getInstance().createEditor(document, project)
          editor.getCaretModel.moveToOffset(offset)
        if ApplicationManager.getApplication.isDispatchThread then
          createEditor.run()
        else
          ApplicationManager.getApplication.invokeAndWait(createEditor)

        val ed = editor
        val handlerResult = projectManager.smartReadAction: () =>
          tryHighlightUsagesHandler(ed, psiFile, document)
        if handlerResult.nonEmpty then return handlerResult
      finally
        if editor != null then
          val editorToRelease = editor
          val releaseEditor: Runnable = () =>
            EditorFactory.getInstance().releaseEditor(editorToRelease)
          if ApplicationManager.getApplication.isDispatchThread then
            releaseEditor.run()
          else
            ApplicationManager.getApplication.invokeAndWait(releaseEditor)

      // Fallback: simple ReferencesSearch
      projectManager.smartReadAction: () =>
        fallbackHighlights(psiFile, document, offset)
    catch
      case e: Exception =>
        System.err.println(s"[DocumentHighlight] Error: ${e.getMessage}")
        Seq.empty

  /** Try HighlightUsagesHandlerFactory EP to get Scala-specific highlight usages. */
  private def tryHighlightUsagesHandler(
    editor: Editor,
    psiFile: PsiFile,
    document: com.intellij.openapi.editor.Document
  ): Seq[DocumentHighlight] =
    val visibleRange = ProperTextRange(0, document.getTextLength)
    val handler = try
      HighlightUsagesHandler.createCustomHandler(editor, psiFile, visibleRange)
    catch
      case _: Exception => null
    if handler == null then return Seq.empty

    try
      val targets = handler.getTargets
      if targets == null || targets.isEmpty then return Seq.empty
      handler.computeUsages(targets)

      val highlights = scala.collection.mutable.ArrayBuffer[DocumentHighlight]()
      for range <- handler.getWriteUsages.asScala do
        val start = PsiUtils.offsetToPosition(document, range.getStartOffset)
        val end = PsiUtils.offsetToPosition(document, range.getEndOffset)
        highlights += DocumentHighlight(org.eclipse.lsp4j.Range(start, end), DocumentHighlightKind.Write)
      for range <- handler.getReadUsages.asScala do
        val start = PsiUtils.offsetToPosition(document, range.getStartOffset)
        val end = PsiUtils.offsetToPosition(document, range.getEndOffset)
        highlights += DocumentHighlight(org.eclipse.lsp4j.Range(start, end), DocumentHighlightKind.Read)
      highlights.toSeq
    catch
      case _: Exception => Seq.empty

  /** Fallback: simple ReferencesSearch for local file scope. */
  private def fallbackHighlights(
    psiFile: PsiFile,
    document: com.intellij.openapi.editor.Document,
    offset: Int
  ): Seq[DocumentHighlight] =
    findTarget(psiFile, offset).map: named =>
      val scope = LocalSearchScope(psiFile)
      val refs = ReferencesSearch.search(named, scope, false).findAll().asScala
      val highlights = scala.collection.mutable.ArrayBuffer[DocumentHighlight]()

      if named.getContainingFile == psiFile then
        highlights += DocumentHighlight(
          PsiUtils.nameElementToRange(document, named),
          DocumentHighlightKind.Write
        )

      for ref <- refs do
        val refElem = ref.getElement
        if refElem.getContainingFile == psiFile then
          highlights += DocumentHighlight(
            PsiUtils.nameElementToRange(document, refElem),
            DocumentHighlightKind.Read
          )

      highlights.toSeq
    .getOrElse(Seq.empty)

  private def findTarget(psiFile: PsiFile, offset: Int): Option[PsiNamedElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: elem =>
      val ref = elem.getReference
      if ref != null then
        Option(ref.resolve()).collect { case n: PsiNamedElement => n }
      else
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
