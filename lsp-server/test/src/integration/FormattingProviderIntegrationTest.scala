package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.FormattingProvider
import org.junit.Assert.*

class FormattingProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = FormattingProvider(projectManager)

  def testFormatBadlyFormattedCode(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val edits = provider.getFormatting(uri)
    assertNotNull("Formatting should return edits", edits)
    assertFalse("Should have at least one edit", edits.isEmpty)

  def testFormatAlreadyFormattedCode(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo: Int =
        |    val x = 42
        |    x + 1
        |""".stripMargin
    )
    val edits = provider.getFormatting(uri)
    assertNotNull(edits)

  def testFormatDoesNotMutateDocument(): Unit =
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
    provider.getFormatting(uri)
    val afterText = getDocument.getText
    assertEquals("Document should not be mutated by formatting", beforeText, afterText)

  def testRangeFormatting(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |def foo={
        |val x=42
        |x+1
        |}
        |""".stripMargin
    )
    val range = Range(Position(1, 0), Position(3, 4))
    val edits = provider.getRangeFormatting(uri, range)
    assertNotNull("Range formatting should return edits", edits)
