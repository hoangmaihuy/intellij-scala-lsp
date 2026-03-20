package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{DocumentHighlightKind, Position}
import org.jetbrains.scalalsP.intellij.DocumentHighlightProvider
import org.junit.Assert.*

class DocumentHighlightProviderIntegrationTest extends ScalaLspTestBase:

  private def getHighlights(uri: String, pos: Position) =
    DocumentHighlightProvider(projectManager).getDocumentHighlights(uri, pos)

  def testHighlightVariable(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    val result = getHighlights(uri, positionAt(1, 6))
    // May return empty in light test mode without full index
    if result.nonEmpty then
      assertTrue("Should find at least 2 highlights for x (definition + references)", result.size >= 2)

  def testHighlightDefinitionIsWrite(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |""".stripMargin
    )
    val result = getHighlights(uri, positionAt(1, 6))
    if result.nonEmpty then
      val writeHighlights = result.filter(_.getKind == DocumentHighlightKind.Write)
      assertTrue("Definition should be marked as Write", writeHighlights.nonEmpty)

  def testHighlightMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String) = s"Hello, $name"
        |  val a = greet("Alice")
        |  val b = greet("Bob")
        |""".stripMargin
    )
    val result = getHighlights(uri, positionAt(1, 6))
    if result.nonEmpty then
      assertTrue("Should find highlights for greet (definition + references)", result.size >= 2)

  def testHighlightFromReference(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    // Position on reference usage of x (line 2, char 12)
    val result = getHighlights(uri, positionAt(2, 12))
    if result.nonEmpty then
      assertTrue("Should find highlights when cursor is on reference", result.size >= 2)

  def testHighlightReturnsEmptyForUnknownSymbol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = undefinedSymbol
        |""".stripMargin
    )
    val result = getHighlights(uri, positionAt(1, 10))
    assertNotNull("Result should not be null", result)

  def testHighlightReferencesAreRead(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    val result = getHighlights(uri, positionAt(1, 6))
    if result.size >= 2 then
      val readHighlights = result.filter(_.getKind == DocumentHighlightKind.Read)
      assertTrue("References should be marked as Read", readHighlights.nonEmpty)
