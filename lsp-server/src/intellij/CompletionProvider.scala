package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementPresentation}
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.google.gson.JsonObject
import com.intellij.psi.{PsiMethod, PsiNamedElement}
import org.eclipse.lsp4j.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction

import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

// Implements textDocument/completion by invoking IntelliJ's completion contributors.
// Completion items are returned lean (no docs, no detail, no auto-import edits).
// Those fields are lazy-loaded via completionItem/resolve using a cached lookup elements array.
class CompletionProvider(projectManager: IntellijProjectManager):

  // Cache fields for resolve support
  private val requestIdCounter = AtomicLong(0L)
  @volatile private var cachedElements: Array[LookupElement] = Array.empty
  @volatile private var cachedRequestId: Long = -1L
  @volatile private var cacheTimestamp: Long = 0L
  @volatile private var cachedDocument: com.intellij.openapi.editor.Document = null
  private val CacheTtlMs = 30_000L

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

  def resolveCompletion(item: CompletionItem): CompletionItem =
    try
      val data = item.getData match
        case obj: JsonObject => obj
        case _ => return item
      val requestId = data.get("requestId").getAsLong
      val index = data.get("index").getAsInt

      if requestId != cachedRequestId then return item
      if System.currentTimeMillis() - cacheTimestamp > CacheTtlMs then return item
      if index < 0 || index >= cachedElements.length then return item

      val elem = cachedElements(index)
      val doc = cachedDocument
      projectManager.smartReadAction: () =>
        // Populate detail from LookupElementPresentation
        val presentation = LookupElementPresentation()
        elem.renderElement(presentation)
        val detail = Seq(
          Option(presentation.getTypeText).filter(_.nonEmpty),
          Option(presentation.getTailText).filter(_.nonEmpty)
        ).flatten.mkString(" ")
        if detail.nonEmpty then item.setDetail(detail)

        // Populate documentation via LanguageDocumentation
        val psi = elem.getPsiElement
        if psi != null then
          try
            val lang = psi.getLanguage
            val docProvider = LanguageDocumentation.INSTANCE.forLanguage(lang)
            if docProvider != null then
              val docText = docProvider.generateDoc(psi, null)
              if docText != null && docText.nonEmpty then
                val clean = HoverProvider.htmlToMarkdown(docText)
                if clean.nonEmpty then
                  item.setDocumentation(MarkupContent(MarkupKind.MARKDOWN, clean))
          catch case _: Exception => ()

        // Populate auto-import edits
        if doc != null then
          getAutoImportEdit(elem, doc).foreach: edit =>
            item.setAdditionalTextEdits(java.util.List.of(edit))

        // Generate snippet insert text for methods with parameters
        if psi != null then
          generateSnippet(psi, item)

      item
    catch
      case e: Exception =>
        System.err.println(s"[CompletionProvider] Resolve error: ${e.getMessage}")
        item

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

      // Store in cache for resolve
      val elements = lookupElements.take(200).toArray
      val newRequestId = requestIdCounter.incrementAndGet()
      cachedElements = elements
      cachedRequestId = newRequestId
      cacheTimestamp = System.currentTimeMillis()
      cachedDocument = document

      // Convert to lean LSP CompletionItems (no detail, no docs, no auto-import)
      elements.zipWithIndex.map: (elem, idx) =>
        toLspCompletionItem(elem, newRequestId, idx)
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
    requestId: Long,
    sortIndex: Int
  ): CompletionItem =
    val item = CompletionItem()
    item.setLabel(elem.getLookupString)

    // Determine CompletionItemKind
    item.setKind(getCompletionKind(elem))

    // Sort order
    item.setSortText(f"$sortIndex%05d")

    // Insert text
    item.setInsertText(elem.getLookupString)
    item.setInsertTextFormat(InsertTextFormat.PlainText)

    // Data field for resolve (requestId + index)
    val data = JsonObject()
    data.addProperty("requestId", requestId)
    data.addProperty("index", sortIndex)
    item.setData(data)

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

  private def hasOverloads(fn: PsiNamedElement): Boolean =
    val name = fn.getName
    val parent = fn.getParent
    if parent == null || name == null then false
    else parent.getChildren.count {
      case f: PsiNamedElement => f.getName == name
      case _ => false
    } > 1

  private def generateSnippet(psi: com.intellij.psi.PsiElement, item: CompletionItem): Unit =
    val methodName = item.getLabel
    psi match
      case fn: ScFunction =>
        val params = fn.parameters
        if params.isEmpty then
          // Zero-param: insert as plain text (no change needed, already set)
          ()
        else if hasOverloads(fn) then
          // Overloaded: just insert the name, let signature help guide
          item.setInsertText(methodName)
          item.setInsertTextFormat(InsertTextFormat.PlainText)
        else
          // Generate snippet with placeholders
          val placeholders = params.zipWithIndex.map { (p, i) =>
            s"$${${i + 1}:${p.getName}}"
          }.mkString(", ")
          item.setInsertText(s"$methodName($placeholders)")
          item.setInsertTextFormat(InsertTextFormat.Snippet)
      case method: PsiMethod =>
        val params = method.getParameterList.getParameters
        if params.isEmpty then
          // Zero-param: insert as plain text
          ()
        else if hasOverloads(method) then
          // Overloaded: just insert the name
          item.setInsertText(methodName)
          item.setInsertTextFormat(InsertTextFormat.PlainText)
        else
          // Generate snippet with placeholders
          val placeholders = params.zipWithIndex.map { (p, i) =>
            s"$${${i + 1}:${p.getName}}"
          }.mkString(", ")
          item.setInsertText(s"$methodName($placeholders)")
          item.setInsertTextFormat(InsertTextFormat.Snippet)
      case _ => ()

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
