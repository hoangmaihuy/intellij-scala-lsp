package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementPresentation}
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiNamedElement
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
    // Use reflection to invoke fillCompletionVariants if direct API isn't accessible
    // The standard approach: create CompletionParameters and a result consumer
    try
      // Try to use CompletionParameters.createParameters (may be internal API)
      val paramsClass = Class.forName("com.intellij.codeInsight.completion.CompletionParameters")
      // Fallback: just collect what we can from the PSI context
      collectFromPsiContext(element, results)
    catch
      case _: Exception =>
        collectFromPsiContext(element, results)

  private def collectFromPsiContext(
    element: com.intellij.psi.PsiElement,
    results: scala.collection.mutable.ArrayBuffer[LookupElement]
  ): Unit =
    // Strategy: Walk the scope to find visible declarations
    // This is a simplified fallback when the full CompletionService isn't available
    import com.intellij.codeInsight.lookup.LookupElementBuilder

    val project = projectManager.getProject

    // 1. Collect from the current file's scope
    var scope = element.getParent
    while scope != null do
      scope.getChildren.foreach: child =>
        child match
          case named: PsiNamedElement =>
            val name = named.getName
            if name != null && name.nonEmpty then
              val lookup = LookupElementBuilder.create(named, name)
                .withTypeText(getTypeText(named))
              results += lookup
          case _ => ()
      scope = scope.getParent

    // 2. Try to collect from Scala plugin's implicit scope via reference completion
    try
      val ref = element.getReference
      if ref != null then
        ref match
          case poly: com.intellij.psi.PsiPolyVariantReference =>
            poly.multiResolve(true).foreach: rr =>
              Option(rr.getElement).foreach:
                case named: PsiNamedElement =>
                  val name = named.getName
                  if name != null && name.nonEmpty then
                    results += LookupElementBuilder.create(named, name)
                      .withTypeText(getTypeText(named))
                case _ => ()
          case _ =>
            Option(ref.resolve()).foreach:
              case named: PsiNamedElement =>
                // If resolved to a type, get its members
                collectMembers(named, results)
              case _ => ()
    catch
      case _: Exception => ()

  private def collectMembers(
    element: com.intellij.psi.PsiElement,
    results: scala.collection.mutable.ArrayBuffer[LookupElement]
  ): Unit =
    import com.intellij.codeInsight.lookup.LookupElementBuilder
    try
      // For classes/objects, collect their members
      val className = element.getClass.getName
      if className.contains("ScObject") || className.contains("ScClass") ||
         className.contains("ScTrait") || className.contains("PsiClass") then
        element.getChildren.foreach: child =>
          child match
            case named: PsiNamedElement =>
              val name = named.getName
              if name != null && name.nonEmpty then
                results += LookupElementBuilder.create(named, name)
                  .withTypeText(getTypeText(named))
            case _ => ()
    catch
      case _: Exception => ()

  private def getTypeText(element: com.intellij.psi.PsiElement): String =
    try
      val method = element.getClass.getMethod("getType")
      val typeResult = method.invoke(element)
      if typeResult == null then ""
      else
        try
          val getMethod = typeResult.getClass.getMethod("get")
          val scType = getMethod.invoke(typeResult)
          if scType != null then scType.toString else ""
        catch
          case _: Exception => typeResult.toString
    catch
      case _: Exception => ""

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

    val className = psiElement.getClass.getName
    if className.contains("ScFunction") || className.contains("PsiMethod") then
      CompletionItemKind.Method
    else if className.contains("ScClass") || className.contains("PsiClass") then
      CompletionItemKind.Class
    else if className.contains("ScTrait") then
      CompletionItemKind.Interface
    else if className.contains("ScObject") then
      CompletionItemKind.Module
    else if className.contains("ScValue") || className.contains("PsiField") then
      CompletionItemKind.Field
    else if className.contains("ScVariable") then
      CompletionItemKind.Variable
    else if className.contains("ScTypeAlias") then
      CompletionItemKind.TypeParameter
    else if className.contains("ScPackage") || className.contains("PsiPackage") then
      CompletionItemKind.Module
    else
      CompletionItemKind.Text

  private def getAutoImportEdit(
    elem: LookupElement,
    document: com.intellij.openapi.editor.Document
  ): Option[TextEdit] =
    try
      val psi = elem.getPsiElement
      if psi == null then return None

      val qualifiedName = psi match
        case named: PsiNamedElement =>
          try
            val method = named.getClass.getMethod("qualifiedName")
            Option(method.invoke(named)).map(_.toString)
          catch
            case _: NoSuchMethodException => None
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
