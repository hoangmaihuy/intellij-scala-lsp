package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*

import java.util.{List as JList}
import scala.jdk.CollectionConverters.*

object SemanticTokensProvider:

  val tokenTypes: JList[String] = JList.of(
    "keyword",       // 0
    "type",          // 1
    "class",         // 2
    "interface",     // 3
    "enum",          // 4
    "method",        // 5
    "property",      // 6
    "variable",      // 7
    "parameter",     // 8
    "typeParameter", // 9
    "string",        // 10
    "number",        // 11
    "comment",       // 12
    "function"       // 13
  )

  val tokenModifiers: JList[String] = JList.of(
    "declaration",   // bit 0
    "static",        // bit 1
    "abstract",      // bit 2
    "readonly",      // bit 3
    "modification",  // bit 4
    "documentation", // bit 5
    "lazy"           // bit 6
  )

  val legend: SemanticTokensLegend = SemanticTokensLegend(tokenTypes, tokenModifiers)

  val typeMapping: Map[String, Int] = Map(
    "SCALA_KEYWORD" -> 0,
    "SCALA_CLASS" -> 2,
    "SCALA_TRAIT" -> 3,
    "SCALA_OBJECT" -> 2,
    "SCALA_CASE_CLASS" -> 2,
    "SCALA_TYPE_ALIAS" -> 1,
    "SCALA_LOCAL_VARIABLE" -> 7,
    "SCALA_MUTABLE_LOCAL_VARIABLE" -> 7,
    "SCALA_PARAMETER" -> 8,
    "SCALA_METHOD" -> 5,
    "SCALA_METHOD_CALL" -> 5,
    "SCALA_FUNCTION" -> 13,
    "SCALA_TYPE_PARAMETER" -> 9,
    "SCALA_STRING" -> 10,
    "SCALA_NUMBER" -> 11,
    "SCALA_LINE_COMMENT" -> 12,
    "SCALA_BLOCK_COMMENT" -> 12,
    "SCALA_DOC_COMMENT" -> 12,
    "SCALA_FIELD" -> 6,
    "SCALA_PROPERTY" -> 6,
    "DEFAULT_KEYWORD" -> 0,
    "DEFAULT_STRING" -> 10,
    "DEFAULT_NUMBER" -> 11,
    "DEFAULT_LINE_COMMENT" -> 12,
    "DEFAULT_BLOCK_COMMENT" -> 12,
    "DEFAULT_DOC_COMMENT" -> 12
  )

  val modifierMapping: Map[String, Int] = Map(
    "SCALA_TRAIT" -> 4,           // abstract, bit 2
    "SCALA_OBJECT" -> 2,          // static, bit 1
    "SCALA_CASE_CLASS" -> 8,      // readonly, bit 3
    "SCALA_DOC_COMMENT" -> 32,    // documentation, bit 5
    "SCALA_IMPLICIT_CONVERSION" -> 16, // modification, bit 4
    "SCALA_IMPLICIT_PARAMETER" -> 16
  )

class SemanticTokensProvider(projectManager: IntellijProjectManager):

  import SemanticTokensProvider.*

  def getSemanticTokensFull(uri: String): SemanticTokens =
    try
      computeTokens(uri, None)
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokens] Error computing full tokens: ${e.getMessage}")
        SemanticTokens(java.util.Collections.emptyList())

  def getSemanticTokensRange(uri: String, range: Range): SemanticTokens =
    try
      computeTokens(uri, Some(range))
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokens] Error computing range tokens: ${e.getMessage}")
        SemanticTokens(java.util.Collections.emptyList())

  private def computeTokens(uri: String, rangeOpt: Option[Range]): SemanticTokens =
    projectManager.smartReadAction: () =>
      val result = for
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val project = projectManager.getProject
        val highlights = DaemonCodeAnalyzerImpl.getHighlights(
          document,
          HighlightSeverity.INFORMATION,
          project
        )

        if highlights == null then SemanticTokens(java.util.Collections.emptyList())
        else
          // Filter highlights with known type mappings
          val filtered = highlights.asScala.filter: h =>
            h.`type`.getAttributesKey != null &&
              typeMapping.contains(h.`type`.getAttributesKey.getExternalName)

          // Apply range filter if specified
          val rangeFiltered = rangeOpt match
            case Some(range) =>
              val rangeStartOffset = PsiUtils.positionToOffset(document, range.getStart)
              val rangeEndOffset = PsiUtils.positionToOffset(document, range.getEnd)
              filtered.filter: h =>
                h.startOffset >= rangeStartOffset && h.endOffset <= rangeEndOffset
            case None => filtered

          // Sort by position
          val sorted = rangeFiltered.toSeq.sortBy(h => (h.startOffset, h.endOffset))

          // Delta-encode
          var prevLine = 0
          var prevChar = 0
          val data = new java.util.ArrayList[Integer]()

          for h <- sorted do
            val keyName = h.`type`.getAttributesKey.getExternalName
            val tokenType = typeMapping(keyName)
            val tokenModifiers = modifierMapping.getOrElse(keyName, 0)

            val startPos = PsiUtils.offsetToPosition(document, h.startOffset)
            val length = h.endOffset - h.startOffset

            val deltaLine = startPos.getLine - prevLine
            val deltaStartChar =
              if deltaLine == 0 then startPos.getCharacter - prevChar
              else startPos.getCharacter

            data.add(deltaLine)
            data.add(deltaStartChar)
            data.add(length)
            data.add(tokenType)
            data.add(tokenModifiers)

            prevLine = startPos.getLine
            prevChar = startPos.getCharacter

          SemanticTokens(data)

      result.getOrElse(SemanticTokens(java.util.Collections.emptyList()))
