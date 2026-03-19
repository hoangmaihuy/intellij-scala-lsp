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
