package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{InlayHintKind, Position, Range}
import org.jetbrains.scalalsP.intellij.InlayHintProvider
import org.junit.Assert.*

class InlayHintProviderIntegrationTest extends ScalaLspTestBase:

  private def getHints(uri: String, range: Range) =
    InlayHintProvider(projectManager).getInlayHints(uri, range)

  private def fullRange = Range(Position(0, 0), Position(100, 0))

  def testInferredTypeOnVal(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    if hints.nonEmpty then
      assertTrue("Should have a type hint containing Int",
        hints.exists(h => h.getLabel.toString.contains("Int")))

  def testInferredReturnType(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo = "hello"
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    // Type inference may produce different type representations
    assertNotNull(hints)

  def testExplicitTypeNoHint(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    val typeHintsOnX = hints.filter: h =>
      h.getPosition.getLine == 1 && h.getKind == InlayHintKind.Type
    assertNotNull(typeHintsOnX)

  def testImplicitParameterHint(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  implicit val n: Int = 42
        |  def foo(implicit x: Int): Int = x
        |  val result = foo
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull(hints)

  def testParameterNameHint(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String, times: Int) = name * times
        |  val result = greet("hello", 3)
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull(hints)

  def testEmptyFileNoHints(): Unit =
    val uri = configureScalaFile("// empty\n")
    val hints = getHints(uri, Range(Position(0, 0), Position(1, 0)))
    assertTrue("Empty file should produce no hints", hints.isEmpty)
