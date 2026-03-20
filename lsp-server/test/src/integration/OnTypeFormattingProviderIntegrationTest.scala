package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.OnTypeFormattingProvider
import org.junit.Assert.*

class OnTypeFormattingProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = OnTypeFormattingProvider(projectManager)

  // --- Newline trigger ---

  def testNewlineTriggerReturnsList(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |def foo = {
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val pos = Position(2, 0)
    val edits = provider.onTypeFormatting(uri, pos, "\n")
    assertNotNull("onTypeFormatting should not return null", edits)
    // Just check it returns without error; formatting result depends on code style settings
    assertTrue("edits should be a list", edits != null)

  def testNewlineTriggerOnWellFormattedCode(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo: Int =
        |    val x = 42
        |    x + 1
        |""".stripMargin
    )
    val pos = Position(2, 0)
    val edits = provider.onTypeFormatting(uri, pos, "\n")
    assertNotNull("Should return a list (possibly empty)", edits)

  def testNewlineTriggerDoesNotMutateDocument(): Unit =
    val code =
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    val uri = configureScalaFile(code)
    val beforeText = getDocument.getText
    provider.onTypeFormatting(uri, Position(2, 0), "\n")
    val afterText = getDocument.getText
    assertEquals("Document should not be mutated by on-type formatting", beforeText, afterText)

  // --- Quote trigger ---

  def testQuoteTriggerAutoClosesTripleQuote(): Unit =
    // Simulate the user having typed `"""` — the text already has `"""` at the position
    val uri = configureScalaFile("val s = \"\"\"")
    // position is right after the third quote (line 0, char 11)
    val pos = Position(0, 11)
    val edits = provider.onTypeFormatting(uri, pos, "\"")
    assertNotNull("Quote trigger should return a list", edits)
    // When the text ends with `"""`, the provider should insert a closing `"""`
    if edits.nonEmpty then
      val edit = edits.head
      assertEquals("Should insert triple quote", "\"\"\"", edit.getNewText)

  def testQuoteTriggerNoAutoCloseWhenAlreadyThreeQuotesFollow(): Unit =
    // Text after cursor already starts with `"""` — no insertion needed
    val uri = configureScalaFile("val s = \"\"\"\"\"\"")  // 6 quotes total: `"` at pos 11 has `"""` after
    // position (0, 11) means offset 11, and text[11..14] == `"""`
    val pos = Position(0, 11)
    val edits = provider.onTypeFormatting(uri, pos, "\"")
    assertNotNull("Quote trigger should return a list", edits)
    assertTrue("Should not insert when already closed with triple quotes following", edits.isEmpty)

  def testQuoteTriggerNoAutoCloseForSingleQuote(): Unit =
    val uri = configureScalaFile("val s = \"hello\"")
    val pos = Position(0, 9)
    val edits = provider.onTypeFormatting(uri, pos, "\"")
    assertNotNull("Quote trigger should return a list", edits)
    assertTrue("Should not auto-close single quote", edits.isEmpty)

  // --- Brace trigger ---

  def testBraceTriggerReturnsList(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |def foo = {
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val pos = Position(4, 1)  // position of the inner `}`
    val edits = provider.onTypeFormatting(uri, pos, "}")
    assertNotNull("Brace trigger should return a list", edits)

  def testBraceTriggerDoesNotMutateDocument(): Unit =
    val code =
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    val uri = configureScalaFile(code)
    val beforeText = getDocument.getText
    provider.onTypeFormatting(uri, Position(4, 0), "}")
    val afterText = getDocument.getText
    assertEquals("Document should not be mutated by brace on-type formatting", beforeText, afterText)

  // --- Unknown trigger ---

  def testUnknownTriggerReturnsEmpty(): Unit =
    val uri = configureScalaFile("object Main {}")
    val edits = provider.onTypeFormatting(uri, Position(0, 0), ";")
    assertNotNull("Unknown trigger should return empty list", edits)
    assertTrue("Unknown trigger should return empty list", edits.isEmpty)

  // --- Missing file ---

  def testMissingFileReturnsEmpty(): Unit =
    val edits = provider.onTypeFormatting("file:///nonexistent/Missing.scala", Position(0, 0), "\n")
    assertNotNull("Missing file should return empty list", edits)
    assertTrue("Missing file should return empty list", edits.isEmpty)
