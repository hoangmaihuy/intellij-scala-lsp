package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.hint.ShowParameterInfoContext
import com.intellij.lang.parameterInfo.{LanguageParameterInfo, ParameterInfoHandler, ParameterInfoUIContext, UpdateParameterInfoContext}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.psi.{PsiElement, PsiFile, PsiMethod, PsiNamedElement}
import org.eclipse.lsp4j.{ParameterInformation, Position, SignatureHelp, SignatureInformation}

import java.awt.Color
import scala.jdk.CollectionConverters.*

class SignatureHelpProvider(projectManager: IntellijProjectManager):

  def getSignatureHelp(uri: String, position: Position): Option[SignatureHelp] =
    try
      // Resolve file references in a read action first
      val (psiFile, document) = projectManager.smartReadAction: () =>
        (for
          pf <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          doc <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield (pf, doc)).getOrElse(return None)
      computeSignatureHelp(psiFile, document, position)
    catch
      case e: Exception =>
        System.err.println(s"[SignatureHelp] Error: ${e.getMessage}")
        None

  private def computeSignatureHelp(
    psiFile: PsiFile,
    document: com.intellij.openapi.editor.Document,
    position: Position
  ): Option[SignatureHelp] =
    val offset = PsiUtils.positionToOffset(document, position)
    val project = psiFile.getProject
    val language = psiFile.getLanguage
    val handlers = LanguageParameterInfo.INSTANCE.allForLanguage(language)
    if handlers == null || handlers.isEmpty then return None

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
      // Try at current offset first, then offset-1 (handles cursor right after '(')
      val offsets = if offset > 0 then Seq(offset, offset - 1) else Seq(offset)
      var result: Option[SignatureHelp] = None
      for off <- offsets if result.isEmpty do
        // moveToOffset must happen on EDT
        val moveCaret: Runnable = () => ed.getCaretModel.moveToOffset(off)
        if ApplicationManager.getApplication.isDispatchThread then moveCaret.run()
        else ApplicationManager.getApplication.invokeAndWait(moveCaret)
        result = projectManager.smartReadAction: () =>
          handlers.asScala.iterator.flatMap: handler =>
            tryHandler(handler.asInstanceOf[ParameterInfoHandler[PsiElement, Any]], psiFile, ed, project, off, offset)
          .nextOption()
      result
    finally
      if editor != null then
        val editorToRelease = editor
        val releaseEditor: Runnable = () =>
          EditorFactory.getInstance().releaseEditor(editorToRelease)
        if ApplicationManager.getApplication.isDispatchThread then
          releaseEditor.run()
        else
          ApplicationManager.getApplication.invokeAndWait(releaseEditor)

  private def tryHandler(
    handler: ParameterInfoHandler[PsiElement, Any],
    psiFile: PsiFile,
    editor: Editor,
    project: Project,
    findOffset: Int,
    originalOffset: Int
  ): Option[SignatureHelp] =
    // Step 1: Find the parameter owner and collect signature descriptors
    val createCtx = new ShowParameterInfoContext(editor, project, psiFile, findOffset, -1)
    val parameterOwner = try
      handler.findElementForParameterInfo(createCtx)
    catch
      case _: Exception => return None
    if parameterOwner == null then return None

    val items = createCtx.getItemsToShow
    if items == null || items.isEmpty then return None

    // Step 2: Determine active parameter index using the ORIGINAL offset (not the fallback)
    // Note: moveToOffset is safe here because the handler's updateParameterInfo reads getOffset()
    // from the context, not the editor caret. We set getOffset to return originalOffset directly.
    val activeParamHolder = new Array[Int](1)
    val compEnabled = new Array[Boolean](items.length)
    val updateCtx = new UpdateParameterInfoContext:
      private var paramOwner: PsiElement = null
      private var highlightedParam: Any = null
      override def removeHint(): Unit = ()
      override def setParameterOwner(o: PsiElement): Unit = paramOwner = o
      override def getParameterOwner: PsiElement = paramOwner
      override def setHighlightedParameter(parameter: Any): Unit = highlightedParam = parameter
      override def getHighlightedParameter: Any = highlightedParam
      override def setCurrentParameter(index: Int): Unit = activeParamHolder(0) = index
      override def isUIComponentEnabled(index: Int): Boolean = if index >= 0 && index < compEnabled.length then compEnabled(index) else false
      override def setUIComponentEnabled(index: Int, b: Boolean): Unit = if index >= 0 && index < compEnabled.length then compEnabled(index) = b
      override def getParameterListStart: Int = originalOffset
      override def getObjectsToView: Array[Object] = items
      override def isPreservedOnHintHidden: Boolean = false
      override def setPreservedOnHintHidden(value: Boolean): Unit = ()
      override def isInnermostContext: Boolean = false
      override def isSingleParameterInfo: Boolean = false
      override def getCustomContext: UserDataHolderEx = null
      override def getProject: Project = psiFile.getProject
      override def getFile: PsiFile = psiFile
      override def getOffset: Int = originalOffset
      override def getEditor: Editor = editor
    updateCtx.setParameterOwner(parameterOwner)
    try handler.updateParameterInfo(parameterOwner, updateCtx)
    catch case _: Exception => ()
    val activeParam = activeParamHolder(0)

    // Step 3: Resolve method name from the parameter owner's parent (the method call expression)
    val methodName = resolveMethodName(parameterOwner)

    // Step 4: Render each signature via updateUI
    val signatures = items.toList.flatMap: item =>
      val textHolder = new Array[String](1)
      val rawHolder = new Array[Boolean](1)
      val uiCtx = new ParameterInfoUIContext:
        private var enabled: Boolean = true
        override def setupUIComponentPresentation(
          text: String, highlightStartOffset: Int, highlightEndOffset: Int,
          isDisabled: Boolean, strikeout: Boolean, isDisabledBeforeHighlight: Boolean, background: Color
        ): String =
          textHolder(0) = text
          rawHolder(0) = false
          text
        override def setupRawUIComponentPresentation(htmlText: String): Unit =
          textHolder(0) = htmlText
          rawHolder(0) = true
        override def isUIComponentEnabled: Boolean = enabled
        override def setUIComponentEnabled(e: Boolean): Unit = enabled = e
        override def getCurrentParameterIndex: Int = activeParam
        override def getParameterOwner: PsiElement = parameterOwner
        override def isSingleOverload: Boolean = false
        override def isSingleParameterInfo: Boolean = false
        override def getDefaultParameterColor: Color = null

      try
        handler.updateUI(item, uiCtx)
        val text = textHolder(0)
        if text != null && text.nonEmpty then
          val paramText = if rawHolder(0) then stripHtml(text) else text
          if paramText.nonEmpty then
            // Build full signature: methodName(params)
            val label = methodName match
              case Some(name) =>
                if paramText.contains('(') then s"$name$paramText"
                else s"$name($paramText)"
              case None =>
                if paramText.contains('(') then paramText
                else s"($paramText)"
            val sig = SignatureInformation(label)
            val params = extractParameters(label)
            if params.nonEmpty then sig.setParameters(params.asJava)
            Some(sig)
          else None
        else None
      catch
        case _: Exception => None

    if signatures.isEmpty then return None

    val help = SignatureHelp()
    help.setSignatures(signatures.asJava)
    help.setActiveSignature(0)
    help.setActiveParameter(activeParam)
    Some(help)

  /** Resolve the method name from the argument list element's parent chain. */
  private def resolveMethodName(parameterOwner: PsiElement): Option[String] =
    // Walk up from the argument list to find the method call, then resolve the method name
    var elem = parameterOwner.getParent
    while elem != null do
      // Try to get the method reference from the call expression
      val ref = try
        val m = elem.getClass.getMethod("getInvokedExpr")
        Option(m.invoke(elem)).map(_.asInstanceOf[PsiElement])
      catch case _: Exception =>
        try
          val m = elem.getClass.getMethod("getMethodExpression")
          Option(m.invoke(elem)).map(_.asInstanceOf[PsiElement])
        catch case _: Exception => None

      ref match
        case Some(refElem) =>
          // Try to get the reference name
          return try
            val nameMethod = refElem.getClass.getMethod("refName")
            Option(nameMethod.invoke(refElem)).map(_.toString)
          catch case _: Exception =>
            refElem match
              case named: PsiNamedElement => Option(named.getName)
              case _ => Some(refElem.getText.split("\\.").last.trim)
        case None =>
          // Check if the parent itself is a named element (e.g., constructor invocation)
          elem match
            case named: PsiNamedElement =>
              return Option(named.getName)
            case _ =>

      elem = elem.getParent
    None

  private def extractParameters(text: String): List[ParameterInformation] =
    val hasParens = text.contains('(')
    if hasParens then
      val params = scala.collection.mutable.ListBuffer.empty[ParameterInformation]
      var i = 0
      while i < text.length do
        if text.charAt(i) == '(' then
          val start = i + 1
          var depth = 1
          var j = start
          while j < text.length && depth > 0 do
            text.charAt(j) match
              case '(' => depth += 1
              case ')' => depth -= 1
              case _ =>
            if depth > 0 then j += 1
          val inner = text.substring(start, j)
          splitParams(inner).foreach: p =>
            val trimmed = p.trim
            if trimmed.nonEmpty then params += ParameterInformation(trimmed)
          i = j + 1
        else
          i += 1
      params.toList
    else
      splitParams(text).flatMap: p =>
        val trimmed = p.trim
        if trimmed.nonEmpty && trimmed != "<no parameters>" then Some(ParameterInformation(trimmed))
        else None

  private def splitParams(s: String): List[String] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    var depth = 0
    var start = 0
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '(' | '[' | '{' => depth += 1
        case ')' | ']' | '}' => depth -= 1
        case ',' if depth == 0 =>
          parts += s.substring(start, i)
          start = i + 1
        case _ =>
      i += 1
    if start < s.length then parts += s.substring(start)
    parts.toList

  private def stripHtml(html: String): String =
    html.replaceAll("<[^>]+>", "").trim
