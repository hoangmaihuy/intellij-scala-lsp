package org.jetbrains.scalalsP.intellij

import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.{FoldingRange, FoldingRangeKind}

// Implements textDocument/foldingRange using IntelliJ's language-specific FoldingBuilder.
class FoldingRangeProvider(projectManager: IntellijProjectManager):

  def getFoldingRanges(uri: String): Seq[FoldingRange] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val lang = psiFile.getLanguage
        val builder = LanguageFolding.INSTANCE.forLanguage(lang)
        if builder == null then Seq.empty
        else
          val descriptors = LanguageFolding.buildFoldingDescriptors(builder, psiFile, document, true)
          descriptors.flatMap: desc =>
            val range = desc.getRange
            val startLine = document.getLineNumber(range.getStartOffset)
            val endLine = document.getLineNumber(range.getEndOffset)
            // Only include ranges that span multiple lines
            if endLine > startLine then
              val foldingRange = new FoldingRange(startLine, endLine)
              // Try to determine the kind from the element
              val kind = inferFoldingKind(desc.getElement)
              if kind != null then foldingRange.setKind(kind)
              Some(foldingRange)
            else None
          .toSeq

      result.getOrElse(Seq.empty)

  private def inferFoldingKind(node: com.intellij.lang.ASTNode): String =
    if node == null then return null
    val elementType = node.getElementType.toString
    if elementType.contains("COMMENT") || elementType.contains("DOC_COMMENT") || elementType.contains("ScalaDoc") then
      FoldingRangeKind.Comment
    else if elementType.contains("IMPORT") then
      FoldingRangeKind.Imports
    else
      FoldingRangeKind.Region
