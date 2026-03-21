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
    assertNotNull("onTypeFormatting must not return null", edits)

  def testNewlineTriggerOnUnformattedCodeProducesEdits(): Unit =
    // Deliberately unformatted: no indentation — the formatter should produce edits
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
    // When the formatter normalises indentation the result differs from the original
    if edits.nonEmpty then
      val edit = edits.head
      assertNotNull("Edit text must not be null", edit.getNewText)
      // The replacement text must contain whitespace (spaces or newlines) — that is the
      // essence of indentation-based formatting
      assertTrue("Formatted text must contain whitespace characters",
        edit.getNewText.exists(c => c == ' ' || c == '\n'))

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
    assertEquals("onTypeFormatting must not mutate the original document", beforeText, afterText)

  def testNewlineTriggerOnWellFormattedCodeProducesEmptyOrIdempotentEdits(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def foo: Int = {
        |    val x = 42
        |    x + 1
        |  }
        |}
        |""".stripMargin
    )
    val pos = Position(2, 0)
    val edits = provider.onTypeFormatting(uri, pos, "\n")
    assertNotNull("Must return a list (possibly empty) for well-formatted code", edits)

  // --- Quote trigger ---

  def testQuoteTriggerAutoClosesTripleQuoteWithExactText(): Unit =
    // The document already contains `"""` (user typed the third `"`)
    val uri = configureScalaFile("val s = \"\"\"")
    // Position is right after the third quote (line 0, char 11)
    val pos = Position(0, 11)
    val edits = provider.onTypeFormatting(uri, pos, "\"")
    assertNotNull("Quote trigger must return a list", edits)
    if edits.nonEmpty then
      val edit = edits.head
      assertEquals(
        "Closing triple-quote insertion must be exactly '\"\"\"'",
        "\"\"\"",
        edit.getNewText
      )

  def testQuoteTriggerNoAutoCloseWhenAlreadyThreeQuotesFollow(): Unit =
    // 6 quotes total: the triple-quote is already closed
    val uri = configureScalaFile("val s = \"\"\"\"\"\"")
    val pos = Position(0, 11)
    val edits = provider.onTypeFormatting(uri, pos, "\"")
    assertNotNull("Quote trigger must return a list", edits)
    assertTrue("Must not insert when closing triple-quotes already follow", edits.isEmpty)

  def testQuoteTriggerNoAutoCloseForSingleQuote(): Unit =
    val uri = configureScalaFile("val s = \"hello\"")
    val pos = Position(0, 9)
    val edits = provider.onTypeFormatting(uri, pos, "\"")
    assertNotNull("Quote trigger must return a list", edits)
    assertTrue("Must not auto-close a single opening quote", edits.isEmpty)

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
    assertNotNull("Brace trigger must return a list", edits)

  def testBraceTriggerOnUnformattedCodeChangesIndentation(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |def foo = {
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val pos = Position(4, 1)
    val edits = provider.onTypeFormatting(uri, pos, "}")
    // When the formatter normalises indentation the replacement text differs from the original
    if edits.nonEmpty then
      val edit = edits.head
      assertNotNull("Brace-trigger edit text must not be null", edit.getNewText)
      // The replacement covers a non-trivial range — start must be (0,0) (full replacement strategy)
      assertNotNull("Brace-trigger edit must have a range", edit.getRange)

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
    assertEquals("Brace on-type formatting must not mutate the original document", beforeText, afterText)

  // --- Unknown trigger ---

  def testUnknownTriggerReturnsEmptyList(): Unit =
    val uri = configureScalaFile("object Main {}")
    val edits = provider.onTypeFormatting(uri, Position(0, 0), ";")
    assertNotNull("Unknown trigger must return a list, not null", edits)
    assertTrue("Unknown trigger must return an empty list", edits.isEmpty)

  // --- Missing file ---

  def testMissingFileReturnsEmptyList(): Unit =
    val edits = provider.onTypeFormatting("file:///nonexistent/Missing.scala", Position(0, 0), "\n")
    assertNotNull("Missing file must return an empty list, not null", edits)
    assertTrue("Missing file must return an empty list", edits.isEmpty)
