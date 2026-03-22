package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.test.MockDocument

class PsiUtilsTest:

  // --- positionToOffset ---

  @Test def testPositionToOffsetAtStartOfDocument(): Unit =
    val doc = MockDocument("hello\nworld\n")
    assertEquals(0, PsiUtils.positionToOffset(doc, Position(0, 0)))

  @Test def testPositionToOffsetAtMiddleOfFirstLine(): Unit =
    val doc = MockDocument("hello\nworld\n")
    assertEquals(3, PsiUtils.positionToOffset(doc, Position(0, 3)))

  @Test def testPositionToOffsetAtStartOfSecondLine(): Unit =
    val doc = MockDocument("hello\nworld\n")
    assertEquals(6, PsiUtils.positionToOffset(doc, Position(1, 0)))

  @Test def testPositionToOffsetAtMiddleOfSecondLine(): Unit =
    val doc = MockDocument("hello\nworld\n")
    assertEquals(9, PsiUtils.positionToOffset(doc, Position(1, 3)))

  @Test def testPositionToOffsetClampsToEndOfLine(): Unit =
    val doc = MockDocument("hello\nworld\n")
    assertEquals(5, PsiUtils.positionToOffset(doc, Position(0, 100)))

  @Test def testPositionToOffsetClampsLineToMax(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val offset = PsiUtils.positionToOffset(doc, Position(100, 0))
    assertTrue(offset >= 0 && offset <= doc.getTextLength)

  @Test def testPositionToOffsetWithEmptyDocument(): Unit =
    val doc = MockDocument("")
    assertEquals(0, PsiUtils.positionToOffset(doc, Position(0, 0)))

  @Test def testPositionToOffsetWithSingleLineNoNewline(): Unit =
    val doc = MockDocument("hello")
    assertEquals(5, PsiUtils.positionToOffset(doc, Position(0, 5)))

  // --- offsetToPosition ---

  @Test def testOffsetToPositionAtStartOfDocument(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 0)
    assertEquals(0, pos.getLine)
    assertEquals(0, pos.getCharacter)

  @Test def testOffsetToPositionAtMiddleOfFirstLine(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 3)
    assertEquals(0, pos.getLine)
    assertEquals(3, pos.getCharacter)

  @Test def testOffsetToPositionAtStartOfSecondLine(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 6)
    assertEquals(1, pos.getLine)
    assertEquals(0, pos.getCharacter)

  @Test def testOffsetToPositionAtEndOfDocument(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 12)
    assertEquals(2, pos.getLine)
    assertEquals(0, pos.getCharacter)

  @Test def testOffsetToPositionClampsNegativeOffset(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, -5)
    assertEquals(0, pos.getLine)
    assertEquals(0, pos.getCharacter)

  @Test def testOffsetToPositionClampsBeyondDocumentLength(): Unit =
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 999)
    assertTrue(pos.getLine >= 0)
    assertTrue(pos.getCharacter >= 0)

  @Test def testOffsetToPositionWithEmptyDocument(): Unit =
    val doc = MockDocument("")
    val pos = PsiUtils.offsetToPosition(doc, 0)
    assertEquals(0, pos.getLine)
    assertEquals(0, pos.getCharacter)

  // --- roundtrip tests ---

  @Test def testPositionToOffsetAndOffsetToPositionRoundtrip(): Unit =
    val doc = MockDocument("package org.example\n\nobject Main:\n  def hello = 42\n")
    for
      line <- 0 until doc.getLineCount
      char <- 0 until (doc.getLineEndOffset(line) - doc.getLineStartOffset(line))
    do
      val pos = Position(line, char)
      val offset = PsiUtils.positionToOffset(doc, pos)
      val roundtrip = PsiUtils.offsetToPosition(doc, offset)
      assertEquals(s"line mismatch at ($line, $char)", line, roundtrip.getLine)
      assertEquals(s"char mismatch at ($line, $char)", char, roundtrip.getCharacter)

  @Test def testOffsetToPositionAndPositionToOffsetRoundtrip(): Unit =
    val text = "package org.example\n\nobject Main:\n  def hello = 42\n"
    val doc = MockDocument(text)
    for offset <- 0 until text.length do
      val pos = PsiUtils.offsetToPosition(doc, offset)
      val roundtrip = PsiUtils.positionToOffset(doc, pos)
      assertEquals(s"offset mismatch at offset $offset -> ($pos)", offset, roundtrip)

  // --- Multi-line document tests ---

  @Test def testPositionToOffsetWithTabs(): Unit =
    val doc = MockDocument("\tclass Foo:\n\t\tdef bar = 1\n")
    assertEquals(1, PsiUtils.positionToOffset(doc, Position(0, 1)))
    assertEquals(14, PsiUtils.positionToOffset(doc, Position(1, 2)))

  @Test def testPositionToOffsetWithUnicode(): Unit =
    val doc = MockDocument("val x = \"héllo\"\n")
    assertEquals(10, PsiUtils.positionToOffset(doc, Position(0, 10)))

  @Test def testPositionToOffsetWithCRLF(): Unit =
    val doc = MockDocument("hello\r\nworld\r\n")
    assertEquals(0, PsiUtils.positionToOffset(doc, Position(0, 0)))
    assertEquals(7, PsiUtils.positionToOffset(doc, Position(1, 0)))

  // --- offsetToRange (fallback for library elements without Documents) ---

  @Test def testOffsetToRangeBasic(): Unit =
    val text = "package cats\n\ntrait Monad[F[_]]:\n  def pure[A](a: A): F[A]\n"
    // "Monad" starts at offset 21, ends at 26
    // Line 0: "package cats\n" (13 chars)  Line 1: "\n" (1 char, starts at 13)  Line 2: "trait Monad..." (starts at 14)
    // Offset 21 = 14 + 7 → line 2, char 7 ("trait M" = 7 chars before "M")
    val range = PsiUtils.offsetToRange(text, 21, 26)
    assertEquals(2, range.getStart.getLine)
    assertEquals(7, range.getStart.getCharacter)
    assertEquals(2, range.getEnd.getLine)
    assertEquals(12, range.getEnd.getCharacter)

  @Test def testOffsetToRangeFirstLine(): Unit =
    val text = "package cats\n\ntrait Monad\n"
    val range = PsiUtils.offsetToRange(text, 0, 7) // "package"
    assertEquals(0, range.getStart.getLine)
    assertEquals(0, range.getStart.getCharacter)
    assertEquals(0, range.getEnd.getLine)
    assertEquals(7, range.getEnd.getCharacter)

  @Test def testOffsetToRangeSpanningLines(): Unit =
    val text = "line0\nline1\nline2\n"
    val range = PsiUtils.offsetToRange(text, 3, 14) // "e0\nline1\nli"
    assertEquals(0, range.getStart.getLine)
    assertEquals(3, range.getStart.getCharacter)
    assertEquals(2, range.getEnd.getLine)
    assertEquals(2, range.getEnd.getCharacter)

  @Test def testOffsetToRangeEmptyText(): Unit =
    val range = PsiUtils.offsetToRange("", 0, 0)
    assertEquals(0, range.getStart.getLine)
    assertEquals(0, range.getStart.getCharacter)

  @Test def testOffsetToRangeNullText(): Unit =
    val range = PsiUtils.offsetToRange(null, 0, 0)
    assertEquals(0, range.getStart.getLine)
    assertEquals(0, range.getStart.getCharacter)

  @Test def testOffsetToRangeMatchesDocumentBased(): Unit =
    // Verify offsetToRange produces the same result as Document-based range computation
    val text = "package org.example\n\nobject Main:\n  val x = 42\n  def hello = \"world\"\n"
    val doc = MockDocument(text)
    // Test several offsets
    val testCases = Seq((0, 7), (21, 27), (34, 39), (44, 49))
    for (start, end) <- testCases do
      val docRange = org.eclipse.lsp4j.Range(
        PsiUtils.offsetToPosition(doc, start),
        PsiUtils.offsetToPosition(doc, end)
      )
      val textRange = PsiUtils.offsetToRange(text, start, end)
      assertEquals(s"Start line mismatch at ($start,$end)",
        docRange.getStart.getLine, textRange.getStart.getLine)
      assertEquals(s"Start char mismatch at ($start,$end)",
        docRange.getStart.getCharacter, textRange.getStart.getCharacter)
      assertEquals(s"End line mismatch at ($start,$end)",
        docRange.getEnd.getLine, textRange.getEnd.getLine)
      assertEquals(s"End char mismatch at ($start,$end)",
        docRange.getEnd.getCharacter, textRange.getEnd.getCharacter)
