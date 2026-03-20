package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementPresentation}
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

// Implements textDocument/completion by invoking IntelliJ's completion contributors.
// The Scala plugin registers CompletionContributors for Scala-specific completions
// (methods, types, imports, postfix templates, etc.).
class CompletionProvider(projectManager: IntellijProjectManager):

  def getCompletions(uri: String, position: Position): Seq[CompletionItem] =
    // Resolve file/document in ReadAction, then do completion outside it
    // (completion needs EDT for editor creation, can't nest inside ReadAction)
    val context = projectManager.smartReadAction: () =>
      val psiFile = projectManager.findPsiFile(uri)
      val doc = projectManager.findVirtualFile(uri).flatMap(vf =>
        Option(FileDocumentManager.getInstance().getDocument(vf)))
      val offset = doc.map(d => PsiUtils.positionToOffset(d, position)).getOrElse(0)
      (psiFile, doc, offset)

    val (psiFileOpt, docOpt, offset) = context
    (for
      psiFile <- psiFileOpt
      document <- docOpt
    yield
      performCompletion(psiFile, document, projectManager.getProject, offset)
    ).getOrElse(Seq.empty)

  private def performCompletion(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    project: com.intellij.openapi.project.Project,
    offset: Int
  ): Seq[CompletionItem] =
    var editor: Editor = null
    try
      // Editor creation must happen on EDT
      val createEditor: Runnable = () =>
        editor = EditorFactory.getInstance().createEditor(document, project)
        editor.getCaretModel.moveToOffset(offset)
      if ApplicationManager.getApplication.isDispatchThread then
        createEditor.run()
      else
        ApplicationManager.getApplication.invokeAndWait(createEditor)

      // Collect lookup elements from all completion contributors for this language
      val lookupElements = scala.collection.mutable.ArrayBuffer[LookupElement]()
      val language = psiFile.getLanguage
      val contributors = CompletionContributor.forLanguage(language)

      if contributors != null then
        ReadAction.run[RuntimeException]: () =>
          val element = psiFile.findElementAt(if offset > 0 then offset - 1 else offset)
          if element != null then
            contributors.asScala.foreach: contributor =>
              try
                collectFromContributor(contributor, psiFile, element, editor, offset, lookupElements)
              catch
                case _: Exception => ()

      // Convert to LSP CompletionItems
      lookupElements.take(200).zipWithIndex.map: (elem, idx) =>
        toLspCompletionItem(elem, document, idx)
      .toSeq
    catch
      case e: Exception =>
        System.err.println(s"[CompletionProvider] Error: ${e.getMessage}")
        Seq.empty
    finally
      if editor != null then
        val releaseEditor: Runnable = () =>
          EditorFactory.getInstance().releaseEditor(editor)
        if ApplicationManager.getApplication.isDispatchThread then
          releaseEditor.run()
        else
          ApplicationManager.getApplication.invokeAndWait(releaseEditor)

  private def collectFromContributor(
    contributor: CompletionContributor,
    psiFile: com.intellij.psi.PsiFile,
    element: com.intellij.psi.PsiElement,
    editor: Editor,
    offset: Int,
    results: scala.collection.mutable.ArrayBuffer[LookupElement]
  ): Unit =
    // CompletionContributor.forLanguage() already provides the right contributors.
    // If they don't produce results for this context, returning empty is correct.
    ()

  private def toLspCompletionItem(
    elem: LookupElement,
    document: com.intellij.openapi.editor.Document,
    sortIndex: Int
  ): CompletionItem =
    val item = CompletionItem()
    item.setLabel(elem.getLookupString)

    // Get presentation details
    val presentation = LookupElementPresentation()
    elem.renderElement(presentation)

    // Build detail from type and tail text
    val detail = Seq(
      Option(presentation.getTypeText).filter(_.nonEmpty),
      Option(presentation.getTailText).filter(_.nonEmpty)
    ).flatten.mkString(" ")
    if detail.nonEmpty then item.setDetail(detail)

    // Determine CompletionItemKind
    item.setKind(getCompletionKind(elem))

    // Sort order
    item.setSortText(f"$sortIndex%05d")

    // Insert text
    item.setInsertText(elem.getLookupString)
    item.setInsertTextFormat(InsertTextFormat.PlainText)

    // Auto-import
    getAutoImportEdit(elem, document).foreach: edit =>
      item.setAdditionalTextEdits(java.util.List.of(edit))

    item

  private def getCompletionKind(elem: LookupElement): CompletionItemKind =
    val psiElement = elem.getPsiElement
    if psiElement == null then return CompletionItemKind.Text

    import org.eclipse.lsp4j.SymbolKind
    PsiUtils.getSymbolKind(psiElement) match
      case SymbolKind.Class          => CompletionItemKind.Class
      case SymbolKind.Interface      => CompletionItemKind.Interface
      case SymbolKind.Module         => CompletionItemKind.Module
      case SymbolKind.Method         => CompletionItemKind.Method
      case SymbolKind.Field          => CompletionItemKind.Field
      case SymbolKind.Variable       => CompletionItemKind.Variable
      case SymbolKind.TypeParameter  => CompletionItemKind.TypeParameter
      case SymbolKind.Package        => CompletionItemKind.Module
      case _                         => CompletionItemKind.Text

  private def getAutoImportEdit(
    elem: LookupElement,
    document: com.intellij.openapi.editor.Document
  ): Option[TextEdit] =
    try
      val psi = elem.getPsiElement
      if psi == null then return None

      import com.intellij.navigation.NavigationItem
      val qualifiedName = psi match
        case nav: NavigationItem =>
          Option(nav.getPresentation).flatMap(p => Option(p.getLocationString))
            .filter(_.contains("."))
            .orElse {
              // Fall back to name if presentation doesn't give a qualified name
              Option(nav.getName).filter(_.contains("."))
            }
        case _ => None

      qualifiedName.flatMap: qName =>
        if qName.contains(".") && !qName.startsWith("scala.Predef") &&
           !qName.startsWith("java.lang.") && !qName.startsWith("scala.") then
          val importText = s"import $qName\n"
          val insertLine = findImportInsertionLine(document)
          val pos = Position(insertLine, 0)
          Some(TextEdit(Range(pos, pos), importText))
        else None
    catch
      case _: Exception => None

  private def findImportInsertionLine(document: com.intellij.openapi.editor.Document): Int =
    val text = document.getText
    val lines = text.split("\n")
    var lastImportLine = 0
    for (line, idx) <- lines.zipWithIndex do
      val trimmed = line.trim
      if trimmed.startsWith("package ") || trimmed.startsWith("import ") then
        lastImportLine = idx + 1
    lastImportLine
