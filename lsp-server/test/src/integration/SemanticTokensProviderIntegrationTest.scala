package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
import org.junit.Assert.*

class SemanticTokensProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SemanticTokensProvider(projectManager)

  def testSemanticTokensReturnsNonNull(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |  def foo(y: String): Boolean = y.isEmpty
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    assertNotNull("Should return SemanticTokens", result)
    assertNotNull("Should have data array", result.getData)

  def testSemanticTokensDataIsMultipleOfFive(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    if result.getData != null && !result.getData.isEmpty then
      assertEquals("Token data should be multiple of 5", 0, result.getData.size() % 5)

  def testSemanticTokensRange(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = "hello"
        |  def foo: Int = x
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val range = Range(Position(1, 0), Position(2, 20))
    val result = provider.getSemanticTokensRange(uri, range)
    assertNotNull("Range tokens should return non-null", result)

  def testTokenTypeMappingKnown(): Unit =
    val legend = SemanticTokensProvider.legend
    assertNotNull(legend)
    assertFalse("Legend should have token types", legend.getTokenTypes.isEmpty)
    assertFalse("Legend should have token modifiers", legend.getTokenModifiers.isEmpty)
    assertTrue("Legend should include 'keyword'", legend.getTokenTypes.contains("keyword"))
    assertTrue("Legend should include 'class'", legend.getTokenTypes.contains("class"))
    assertTrue("Legend should include 'method'", legend.getTokenTypes.contains("method"))
