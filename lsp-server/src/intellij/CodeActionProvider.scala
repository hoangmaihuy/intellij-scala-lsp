package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.codeInsight.intention.{IntentionAction, IntentionManager}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.{Editor, EditorFactory}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFileFactory
import com.google.gson.JsonObject
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

// Implements textDocument/codeAction by collecting:
// 1. Quick fixes from IntelliJ's highlighting/inspection results
// 2. Intention actions from IntentionManager
//
// Code actions use lazy resolve: getCodeActions returns actions with a `data` field
// but no workspace edit. The client calls resolveCodeAction to compute the edit.
class CodeActionProvider(projectManager: IntellijProjectManager):

  def getCodeActions(
    uri: String,
    range: Range,
    context: CodeActionContext
  ): Seq[CodeAction] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val project = projectManager.getProject
        val startOffset = PsiUtils.positionToOffset(document, range.getStart)
        val endOffset = PsiUtils.positionToOffset(document, range.getEnd)

        val actions = Seq.newBuilder[CodeAction]

        // 1. Quick fixes from highlighting
        actions ++= collectQuickFixes(uri, document, project, startOffset, endOffset)

        // 2. Intention actions
        actions ++= collectIntentionActions(uri, psiFile, document, project, startOffset)

        actions.result()

      result.getOrElse(Seq.empty)

  // --- Resolve ---

  /** Resolves a code action by computing the workspace edit.
    * Uses a two-phase approach to avoid deadlock:
    *   Phase 1 (inside smartReadAction): collect original text and locate fix/intention
    *   Phase 2 (outside read action): apply fix on a PSI copy, diff to create edit
    */
  def resolveCodeAction(action: CodeAction): CodeAction =
    val data = action.getData match
      case json: JsonObject => json
      case _ => return action

    val actionType = Option(data.get("type")).map(_.getAsString).getOrElse("")
    val uri = Option(data.get("uri")).map(_.getAsString).getOrElse("")

    actionType match
      case "quickfix" => resolveQuickFix(action, data, uri)
      case "intention" => resolveIntention(action, data, uri)
      case _ => action

  private def resolveQuickFix(action: CodeAction, data: JsonObject, uri: String): CodeAction =
    val startOffset = data.get("startOffset").getAsInt
    val endOffset = data.get("endOffset").getAsInt
    val fixIndex = data.get("fixIndex").getAsInt

    // Phase 1: read action — collect original text and locate the fix
    val phase1 = projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val project = projectManager.getProject
        val originalText = document.getText
        val language = psiFile.getLanguage

        // Find the fix
        val highlights = DaemonCodeAnalyzerImpl.getHighlights(
          document, HighlightSeverity.INFORMATION, project
        )
        val fixAction = if highlights != null then
          highlights.asScala
            .filter: h =>
              h.startOffset <= endOffset && h.endOffset >= startOffset &&
              h.getDescription != null && h.getDescription.nonEmpty
            .flatMap: h =>
              val qf = h.quickFixActionRanges
              if qf != null then qf.asScala.map(_.getFirst.getAction) else Seq.empty
            .drop(fixIndex)
            .headOption
        else None

        (originalText, language, fixAction, project)

    phase1 match
      case Some((originalText, language, Some(fixAction), project)) =>
        // Phase 2: outside read action — apply fix on a copy
        applyFixOnCopy(action, uri, originalText, language, project, fixAction)
      case _ => action

  private def resolveIntention(action: CodeAction, data: JsonObject, uri: String): CodeAction =
    val offset = data.get("offset").getAsInt
    val intentionClass = data.get("intentionClass").getAsString

    // Phase 1: read action — collect original text, language, offset, and verify intention class exists
    val phase1 = projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val project = projectManager.getProject
        val originalText = document.getText
        val language = psiFile.getLanguage

        // Verify the intention class exists in the manager
        val intentionManager = IntentionManager.getInstance
        val hasIntention =
          intentionManager != null && intentionManager.getAvailableIntentions != null &&
            intentionManager.getAvailableIntentions.asScala.exists(_.getClass.getName == intentionClass)

        (originalText, language, hasIntention, project)

    phase1 match
      case Some((originalText, language, true, project)) =>
        // Phase 2: outside read action — create PSI copy, find intention, check availability, invoke
        try
          val factory = PsiFileFactory.getInstance(project)
          val copyFile = factory.createFileFromText("Copy.scala", language, originalText, true, false)
          val copyDoc = copyFile.getViewProvider.getDocument
          if copyDoc == null then return action

          var copyEditor: Editor = null
          try
            // Find and invoke the intention on the copy via EDT
            ApplicationManager.getApplication.invokeAndWait: () =>
              WriteCommandAction.runWriteCommandAction(project, (() =>
                copyEditor = EditorFactory.getInstance().createEditor(copyDoc, project)
                copyEditor.getCaretModel.moveToOffset(offset)

                val intentionManager = IntentionManager.getInstance
                val matchingIntention = intentionManager.getAvailableIntentions.asScala.find: intention =>
                  intention.getClass.getName == intentionClass &&
                    (try intention.isAvailable(project, copyEditor, copyFile)
                     catch case _: Exception => false)

                matchingIntention.foreach: intention =>
                  try intention.invoke(project, copyEditor, copyFile)
                  catch case _: Exception => ()
              ): Runnable)

            // Phase 3: diff and create WorkspaceEdit
            val resultText = copyDoc.getText
            if resultText != originalText then
              val edit = createFullDocumentEdit(uri, originalText, resultText)
              action.setEdit(edit)
          finally
            if copyEditor != null then
              val editorToRelease = copyEditor
              ApplicationManager.getApplication.invokeAndWait: () =>
                EditorFactory.getInstance().releaseEditor(editorToRelease)

          action
        catch
          case _: Exception => action
      case _ => action

  private def applyFixOnCopy(
    action: CodeAction,
    uri: String,
    originalText: String,
    language: com.intellij.lang.Language,
    project: com.intellij.openapi.project.Project,
    fixAction: IntentionAction
  ): CodeAction =
    try
      // Create a PSI file copy
      val factory = PsiFileFactory.getInstance(project)
      val copyFile = factory.createFileFromText("Copy.scala", language, originalText, true, false)
      val copyDoc = copyFile.getViewProvider.getDocument
      if copyDoc == null then return action

      // Create an editor for the copy
      var copyEditor: Editor = null
      try
        // Apply the fix via WriteCommandAction + invokeAndWait (OUTSIDE read action)
        ApplicationManager.getApplication.invokeAndWait: () =>
          WriteCommandAction.runWriteCommandAction(project, (() =>
            copyEditor = EditorFactory.getInstance().createEditor(copyDoc, project)
            copyEditor.getCaretModel.moveToOffset(0)
            try fixAction.invoke(project, copyEditor, copyFile)
            catch case _: Exception => ()
          ): Runnable)

        val resultText = copyDoc.getText
        if resultText != originalText then
          val edit = createFullDocumentEdit(uri, originalText, resultText)
          action.setEdit(edit)
      finally
        if copyEditor != null then
          val editorToRelease = copyEditor
          ApplicationManager.getApplication.invokeAndWait: () =>
            EditorFactory.getInstance().releaseEditor(editorToRelease)

      action
    catch
      case _: Exception => action

  private def createFullDocumentEdit(uri: String, originalText: String, newText: String): WorkspaceEdit =
    val lines = originalText.split("\n", -1)
    val lastLine = lines.length - 1
    val lastCol = if lines.nonEmpty then lines.last.length else 0

    val fullRange = Range(
      Position(0, 0),
      Position(lastLine, lastCol)
    )
    val textEdit = TextEdit(fullRange, newText)
    WorkspaceEdit(java.util.Map.of(uri, java.util.List.of(textEdit)))

  // --- Quick Fixes ---

  private def collectQuickFixes(
    uri: String,
    document: com.intellij.openapi.editor.Document,
    project: com.intellij.openapi.project.Project,
    startOffset: Int,
    endOffset: Int
  ): Seq[CodeAction] =
    try
      val highlights = DaemonCodeAnalyzerImpl.getHighlights(
        document, HighlightSeverity.INFORMATION, project
      )
      if highlights == null then return Seq.empty

      var fixIndex = 0
      highlights.asScala
        .filter: h =>
          h.startOffset <= endOffset && h.endOffset >= startOffset &&
          h.getDescription != null && h.getDescription.nonEmpty
        .flatMap: h =>
          val fixes = extractQuickFixes(uri, h, document, startOffset, endOffset, fixIndex)
          fixIndex += fixes.size
          fixes
        .toSeq
    catch
      case _: Exception => Seq.empty

  private def extractQuickFixes(
    uri: String,
    info: HighlightInfo,
    document: com.intellij.openapi.editor.Document,
    startOffset: Int,
    endOffset: Int,
    baseIndex: Int
  ): Seq[CodeAction] =
    try
      val quickFixes = info.quickFixActionRanges
      if quickFixes == null then return Seq.empty

      var idx = baseIndex
      quickFixes.asScala.flatMap: pair =>
        try
          val fixAction = pair.getFirst.getAction
          if fixAction != null then
            val action = CodeAction()
            action.setTitle(fixAction.getText)
            action.setKind(CodeActionKind.QuickFix)

            // Link to source diagnostic
            val diagnostic = Diagnostic()
            diagnostic.setRange(Range(
              PsiUtils.offsetToPosition(document, info.startOffset),
              PsiUtils.offsetToPosition(document, info.endOffset)
            ))
            diagnostic.setMessage(info.getDescription)
            diagnostic.setSeverity(toLspSeverity(info.getSeverity))
            diagnostic.setSource("intellij-scala")
            action.setDiagnostics(java.util.List.of(diagnostic))

            // Add data for lazy resolve
            val data = JsonObject()
            data.addProperty("type", "quickfix")
            data.addProperty("uri", uri)
            data.addProperty("startOffset", startOffset)
            data.addProperty("endOffset", endOffset)
            data.addProperty("fixIndex", idx)
            action.setData(data)
            idx += 1

            Some(action)
          else None
        catch
          case _: Exception => None
      .toSeq
    catch
      case _: Exception => Seq.empty

  // --- Intention Actions ---

  private def collectIntentionActions(
    uri: String,
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    project: com.intellij.openapi.project.Project,
    offset: Int
  ): Seq[CodeAction] =
    var editor: Editor = null
    try
      editor = EditorFactory.getInstance().createEditor(document, project)
      editor.getCaretModel.moveToOffset(offset)

      val intentionManager = IntentionManager.getInstance
      if intentionManager == null then return Seq.empty

      val availableIntentions = intentionManager.getAvailableIntentions
      if availableIntentions == null then return Seq.empty

      availableIntentions.asScala.flatMap: intention =>
        try
          if intention.isAvailable(project, editor, psiFile) then
            val action = CodeAction()
            action.setTitle(intention.getText)
            action.setKind(categorizeIntention(intention))

            // Add data for lazy resolve
            val data = JsonObject()
            data.addProperty("type", "intention")
            data.addProperty("uri", uri)
            data.addProperty("offset", offset)
            data.addProperty("intentionClass", intention.getClass.getName)
            action.setData(data)

            Some(action)
          else None
        catch
          case _: Exception => None
      .toSeq
    catch
      case _: Exception => Seq.empty
    finally
      if editor != null then
        EditorFactory.getInstance().releaseEditor(editor)

  private def categorizeIntention(intention: IntentionAction): String =
    val text = intention.getText.toLowerCase
    if text.contains("extract") then CodeActionKind.RefactorExtract
    else if text.contains("inline") then CodeActionKind.RefactorInline
    else if text.contains("import") then CodeActionKind.Source
    else if text.contains("organize") then CodeActionKind.SourceOrganizeImports
    else CodeActionKind.Refactor

  private[intellij] def toLspSeverity(severity: HighlightSeverity): DiagnosticSeverity =
    if severity.compareTo(HighlightSeverity.ERROR) >= 0 then DiagnosticSeverity.Error
    else if severity.compareTo(HighlightSeverity.WARNING) >= 0 then DiagnosticSeverity.Warning
    else if severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0 then DiagnosticSeverity.Information
    else DiagnosticSeverity.Hint
