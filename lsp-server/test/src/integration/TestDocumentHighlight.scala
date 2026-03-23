package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{DocumentHighlight, Position, Range}

/** Utility for converting document highlight results into Metals-style marked strings.
  *
  * Input format (expected): `<<identifier>>` marks highlighted ranges, `@@` marks cursor position.
  * E.g.: {{{
  *   object Main {
  *     val <<x>> = 42
  *     val y = <<x@@>> + 1
  *   }
  * }}}
  *
  * This matches the test format used in Metals' `BaseDocumentHighlightSuite`.
  */
object TestDocumentHighlight:

  /** Parse the cursor position (`@@`) from a marked string. Returns (clean code without markers, Position).
    * The `@@` marker is placed WITHIN a highlighted identifier (e.g. `<<ab@@c>>`).
    * The returned position points to the character just before `@@` in the clean source. */
  def parseCursorPosition(marked: String): (String, Position) =
    // First remove highlight markers, keeping @@ in place
    val withoutHighlights = marked.replaceAll("(<<|>>)", "")
    val cursorIdx = withoutHighlights.indexOf("@@")
    require(cursorIdx >= 0, "Marked string must contain @@ cursor marker")
    val clean = withoutHighlights.replace("@@", "")
    // cursorIdx points to where @@ was — which is one past the character before it.
    // To land ON the character before @@, subtract 1 if cursor isn't at position 0.
    val adjustedIdx = if cursorIdx > 0 then cursorIdx - 1 else cursorIdx
    val lines = clean.substring(0, adjustedIdx).split("\n", -1)
    val line = lines.length - 1
    val char = lines.last.length
    (clean, Position(line, char))

  /** Render highlight results back into the marked string format (with `<<` `>>` markers).
    * Highlights are inserted in reverse-offset order to preserve positions. */
  def renderHighlights(source: String, highlights: Seq[DocumentHighlight]): String =
    val lines = source.split("\n", -1)
    val lineOffsets = new Array[Int](lines.length)
    var running = 0
    for i <- lines.indices do
      lineOffsets(i) = running
      running += lines(i).length + 1

    // Convert Range to (startOffset, endOffset) pairs
    val ranges = highlights.map: h =>
      val r = h.getRange
      val startOff = lineOffsets(r.getStart.getLine) + r.getStart.getCharacter
      val endOff = lineOffsets(r.getEnd.getLine) + r.getEnd.getCharacter
      (startOff, endOff)

    // Sort by start offset descending so insertions don't shift later positions
    val sorted = ranges.sortBy(-_._1)

    val sb = new StringBuilder(source)
    for (start, end) <- sorted do
      sb.insert(end, ">>")
      sb.insert(start, "<<")
    sb.toString
