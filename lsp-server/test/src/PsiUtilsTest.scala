package org.jetbrains.scalalsP.intellij

import munit.FunSuite
import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.test.MockDocument

class PsiUtilsTest extends FunSuite:

  // --- positionToOffset ---

  test("positionToOffset at start of document"):
    val doc = MockDocument("hello\nworld\n")
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 0)), 0)

  test("positionToOffset at middle of first line"):
    val doc = MockDocument("hello\nworld\n")
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 3)), 3)

  test("positionToOffset at start of second line"):
    val doc = MockDocument("hello\nworld\n")
    assertEquals(PsiUtils.positionToOffset(doc, Position(1, 0)), 6)

  test("positionToOffset at middle of second line"):
    val doc = MockDocument("hello\nworld\n")
    assertEquals(PsiUtils.positionToOffset(doc, Position(1, 3)), 9)

  test("positionToOffset clamps to end of line"):
    val doc = MockDocument("hello\nworld\n")
    // Character 100 on line 0 should clamp to end of line 0
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 100)), 5)

  test("positionToOffset clamps line to max"):
    val doc = MockDocument("hello\nworld\n")
    // Line 100 should clamp to last line
    val offset = PsiUtils.positionToOffset(doc, Position(100, 0))
    assert(offset >= 0 && offset <= doc.getTextLength)

  test("positionToOffset with empty document"):
    val doc = MockDocument("")
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 0)), 0)

  test("positionToOffset with single line no newline"):
    val doc = MockDocument("hello")
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 5)), 5)

  // --- offsetToPosition ---

  test("offsetToPosition at start of document"):
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 0)
    assertEquals(pos.getLine, 0)
    assertEquals(pos.getCharacter, 0)

  test("offsetToPosition at middle of first line"):
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 3)
    assertEquals(pos.getLine, 0)
    assertEquals(pos.getCharacter, 3)

  test("offsetToPosition at start of second line"):
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 6)
    assertEquals(pos.getLine, 1)
    assertEquals(pos.getCharacter, 0)

  test("offsetToPosition at end of document"):
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 12)
    assertEquals(pos.getLine, 2)
    assertEquals(pos.getCharacter, 0)

  test("offsetToPosition clamps negative offset"):
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, -5)
    assertEquals(pos.getLine, 0)
    assertEquals(pos.getCharacter, 0)

  test("offsetToPosition clamps beyond document length"):
    val doc = MockDocument("hello\nworld\n")
    val pos = PsiUtils.offsetToPosition(doc, 999)
    assert(pos.getLine >= 0)
    assert(pos.getCharacter >= 0)

  test("offsetToPosition with empty document"):
    val doc = MockDocument("")
    val pos = PsiUtils.offsetToPosition(doc, 0)
    assertEquals(pos.getLine, 0)
    assertEquals(pos.getCharacter, 0)

  // --- roundtrip tests ---

  test("positionToOffset and offsetToPosition roundtrip"):
    val doc = MockDocument("package org.example\n\nobject Main:\n  def hello = 42\n")
    for
      line <- 0 until doc.getLineCount
      char <- 0 until (doc.getLineEndOffset(line) - doc.getLineStartOffset(line))
    do
      val pos = Position(line, char)
      val offset = PsiUtils.positionToOffset(doc, pos)
      val roundtrip = PsiUtils.offsetToPosition(doc, offset)
      assertEquals(roundtrip.getLine, line, s"line mismatch at ($line, $char)")
      assertEquals(roundtrip.getCharacter, char, s"char mismatch at ($line, $char)")

  test("offsetToPosition and positionToOffset roundtrip"):
    val text = "package org.example\n\nobject Main:\n  def hello = 42\n"
    val doc = MockDocument(text)
    for offset <- 0 until text.length do
      val pos = PsiUtils.offsetToPosition(doc, offset)
      val roundtrip = PsiUtils.positionToOffset(doc, pos)
      assertEquals(roundtrip, offset, s"offset mismatch at offset $offset -> ($pos)")

  // --- vfToUri ---

  // vfToUri is tested via LspConversionsTest

  // --- Multi-line document tests ---

  test("positionToOffset with tabs"):
    val doc = MockDocument("\tclass Foo:\n\t\tdef bar = 1\n")
    // Tab is just 1 character in offset terms
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 1)), 1)
    assertEquals(PsiUtils.positionToOffset(doc, Position(1, 2)), 14)

  test("positionToOffset with unicode"):
    val doc = MockDocument("val x = \"héllo\"\n")
    // Each unicode char is still 1 char in Java
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 10)), 10)

  test("positionToOffset with CRLF"):
    val doc = MockDocument("hello\r\nworld\r\n")
    // \r\n — \r counts as a character, \n starts new line
    assertEquals(PsiUtils.positionToOffset(doc, Position(0, 0)), 0)
    // Line 1 starts after \r\n = offset 7
    assertEquals(PsiUtils.positionToOffset(doc, Position(1, 0)), 7)
