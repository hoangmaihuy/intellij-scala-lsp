package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.SemanticTokens
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Utility for converting LSP semantic token data into Metals-style decorated strings.
  *
  * Format: `<<identifier>>/*tokenType,modifier1,modifier2*/`
  *
  * This matches the test format used in Metals' `TestSemanticTokens`, enabling
  * concise, readable test expectations that are directly comparable between projects.
  */
object TestSemanticTokens:

  private val tokenTypes: IndexedSeq[String] =
    SemanticTokensProvider.tokenTypes.asScala.toIndexedSeq

  private val tokenModifiers: IndexedSeq[String] =
    SemanticTokensProvider.tokenModifiers.asScala.toIndexedSeq

  /** Build the decoration string for a token type index and modifier bitmask.
    * E.g. `decorationString(2, 6)` → `"class,definition,readonly"` */
  def decorationString(typeIdx: Int, modBits: Int): String =
    val buffer = ListBuffer.empty[String]
    if typeIdx >= 0 && typeIdx < tokenTypes.size then
      buffer += tokenTypes(typeIdx)
    val bits = modBits.toBinaryString.toCharArray.toList.reverse
    for i <- bits.indices do
      if bits(i) == '1' && i < tokenModifiers.size then
        buffer += tokenModifiers(i)
    buffer.mkString(",")

  /** Convert LSP semantic token data (delta-encoded integers) into a decorated source string.
    *
    * Given source code and the `SemanticTokens.getData` list, produces output like:
    * {{{
    *   object <<Main>>/*class,definition,static*/ {
    *     val <<x>>/*variable,definition,readonly*/ = 42
    *   }
    * }}}
    */
  def semanticString(source: String, tokens: SemanticTokens): String =
    val data = tokens.getData
    if data == null || data.isEmpty then return source
    val dataSize = data.size()

    // Decode delta-encoded tokens into absolute (offset, length, type, modifiers)
    val lines = source.split("\n", -1)
    val lineOffsets = new Array[Int](lines.length)
    var running = 0
    for i <- lines.indices do
      lineOffsets(i) = running
      running += lines(i).length + 1 // +1 for '\n'

    val decoded = scala.collection.mutable.ArrayBuffer[(Int, Int, String)]() // (offset, length, decoration)
    var prevLine = 0
    var prevChar = 0
    var i = 0
    while i + 4 < dataSize do
      val deltaLine = data.get(i).intValue
      val deltaChar = data.get(i + 1).intValue
      val length = data.get(i + 2).intValue
      val tokenType = data.get(i + 3).intValue
      val modifiers = data.get(i + 4).intValue
      val line = prevLine + deltaLine
      val char = if deltaLine == 0 then prevChar + deltaChar else deltaChar
      val offset = if line < lineOffsets.length then lineOffsets(line) + char else 0
      decoded += ((offset, length, decorationString(tokenType, modifiers)))
      prevLine = line
      prevChar = char
      i += 5

    // Sort by offset (should already be sorted, but ensure)
    val sorted = decoded.sortBy(_._1)

    // Build decorated string
    val sb = new StringBuilder
    var pos = 0
    for (offset, length, decoration) <- sorted do
      if offset >= pos && offset + length <= source.length then
        sb.append(source.substring(pos, offset))
        sb.append("<<")
        sb.append(source.substring(offset, offset + length))
        sb.append(">>/*")
        sb.append(decoration)
        sb.append("*/")
        pos = offset + length

    sb.append(source.substring(pos, source.length))
    sb.toString

  /** Strip all decoration markers from a decorated string to recover the original source. */
  def removeDecorations(decorated: String): String =
    decorated
      .replaceAll(raw"/\*[\w,]+\*/", "")
      .replaceAll(raw"<<|>>", "")
