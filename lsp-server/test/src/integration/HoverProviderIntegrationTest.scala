package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.HoverProvider
import org.junit.Assert.*

class HoverProviderIntegrationTest extends ScalaLspTestBase:

  private def getHover(uri: String, pos: Position) =
    HoverProvider(projectManager).getHover(uri, pos)

  def testHoverOnTypedVal(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |""".stripMargin
    )
    val result = getHover(uri, positionAt(1, 6))
    result match
      case Some(hover) =>
        assertNotNull(hover.getContents)
        val content = hover.getContents.getRight.getValue
        assertTrue("Should mention Int", content.contains("Int"))
      case None => ()

  def testHoverOnMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String): String = s"Hello, $name"
        |""".stripMargin
    )
    val result = getHover(uri, positionAt(1, 6))
    result match
      case Some(hover) =>
        val content = hover.getContents.getRight.getValue
        assertTrue("Should contain method info",
          content.contains("greet") || content.contains("String"))
      case None => ()

  def testHoverOnInferredType(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val result = getHover(uri, positionAt(1, 6))
    result match
      case Some(hover) =>
        val content = hover.getContents.getRight.getValue
        assertTrue("Should show inferred type Int", content.contains("Int"))
      case None => ()

  def testHoverOnClassName(): Unit =
    val uri = configureScalaFile(
      """class Foo(val x: Int)
        |object Main:
        |  val f = new Foo(1)
        |""".stripMargin
    )
    val result = getHover(uri, positionAt(2, 14))
    result match
      case Some(hover) =>
        assertNotNull(hover.getContents)
      case None => ()

  def testHoverOnWhitespace(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |
        |  val x = 42
        |""".stripMargin
    )
    val result = getHover(uri, positionAt(1, 0))
    // Should not throw

  def testHoverWithScalaDoc(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  /** Returns the sum of two integers */
        |  def add(a: Int, b: Int): Int = a + b
        |""".stripMargin
    )
    val result = getHover(uri, positionAt(2, 6))
    result match
      case Some(hover) =>
        val content = hover.getContents.getRight.getValue
        assertTrue("Should contain ScalaDoc content",
          content.contains("sum") || content.contains("Int"))
      case None => ()
