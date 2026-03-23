package org.jetbrains.scalalsP.integration

import org.jetbrains.scalalsP.intellij.DocumentHighlightProvider
import org.junit.Assert.*

class DocumentHighlightProviderIntegrationTest extends ScalaLspTestBase:

  private def highlightProvider = DocumentHighlightProvider(projectManager)

  /** Metals-style check: compare marked source with actual highlight results.
    *
    * Format: `<<identifier>>` marks highlighted ranges, `@@` marks cursor position.
    * E.g.: {{{
    *   object Main {
    *     val <<x>> = 42
    *     val y = <<x@@>> + 1
    *   }
    * }}}
    */
  private def check(name: String, expected: String): Unit =
    val (source, position) = TestDocumentHighlight.parseCursorPosition(expected)
    val uri = configureScalaFile(name + ".scala", source)
    myFixture.doHighlighting()
    val highlights = highlightProvider.getDocumentHighlights(uri, position)
    val expectedClean = expected.replace("@@", "")
    val obtained = TestDocumentHighlight.renderHighlights(source, highlights)
    assertEquals(s"Document highlights mismatch for '$name'", expectedClean, obtained)

  def testHighlightVariableUsages(): Unit =
    check("highlightVariableUsages",
      """object Main {
        |  val <<x>> = 42
        |  val y = <<x>> + 1
        |  val z = <<x@@>> * 2
        |}
        |""".stripMargin
    )

  def testHighlightDefinitionAndReferences(): Unit =
    check("highlightDefinitionAndReferences",
      """object Main {
        |  val <<x@@>> = 42
        |  def foo = <<x>> + 1
        |}
        |""".stripMargin
    )

  def testHighlightReferencesFromUsageSite(): Unit =
    check("highlightReferencesFromUsageSite",
      """object Main {
        |  val <<x>> = 42
        |  def foo = <<x>> + 1
        |  def bar = <<x@@>> + 2
        |}
        |""".stripMargin
    )

  def testHighlightMethodFromDefinition(): Unit =
    check("highlightMethodFromDefinition",
      """object Main {
        |  def <<gre@@et>>(name: String): String = s"Hello $$name"
        |  val a = <<greet>>("Alice")
        |  val b = <<greet>>("Bob")
        |}
        |""".stripMargin
    )

  def testHighlightFromReference(): Unit =
    check("highlightFromReference",
      """object Main {
        |  val <<x>> = 42
        |  def foo = <<x@@>> + 1
        |  def bar = <<x>> + 2
        |}
        |""".stripMargin
    )

  def testNoHighlightsForComment(): Unit =
    val source = """object Main {
                   |  // just a comment
                   |}
                   |""".stripMargin
    val uri = configureScalaFile("noHighlightsForComment.scala", source)
    val highlights = highlightProvider.getDocumentHighlights(uri, positionAt(1, 5))
    assertNotNull("Result should not be null", highlights)
    assertTrue("Comments should produce no highlights", highlights.isEmpty)

  def testNoHighlightsForUnresolvableSymbol(): Unit =
    val source = """object Main {
                   |  val x = undefinedSymbol
                   |}
                   |""".stripMargin
    val uri = configureScalaFile("noHighlightsForUnresolvable.scala", source)
    val highlights = highlightProvider.getDocumentHighlights(uri, positionAt(1, 10))
    assertNotNull("Result should not be null", highlights)
    highlights.foreach: h =>
      assertTrue("Any returned highlight must have a valid kind",
        h.getKind == org.eclipse.lsp4j.DocumentHighlightKind.Write ||
        h.getKind == org.eclipse.lsp4j.DocumentHighlightKind.Read)
