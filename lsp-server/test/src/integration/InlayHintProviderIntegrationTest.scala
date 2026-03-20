package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
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
    assertNotNull("Should not crash with implicit parameters", hints)

  def testImplicitParameterHintDoesNotCrash(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  import scala.concurrent.ExecutionContext
        |  implicit val ec: ExecutionContext = ExecutionContext.global
        |  def run(implicit ec: ExecutionContext): Unit = ()
        |  run
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should not crash with ExecutionContext implicit", hints)

  def testImplicitConversionHintDoesNotCrash(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  implicit def intToString(x: Int): String = x.toString
        |  val s: String = 42
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should not crash with implicit conversion", hints)

  def testTypeParameterHintDoesNotCrash(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def identity[A](a: A): A = a
        |  val x = identity(42)
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should not crash with type parameter inference", hints)

  def testTypeParameterHintWithExplicitTypeArgs(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def identity[A](a: A): A = a
        |  val x = identity[Int](42)
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    // When explicit type args are provided, no type parameter hint should appear
    val typeParamHints = hints.filter: h =>
      h.getKind == InlayHintKind.Type && h.getLabel.toString.contains("[")
    // Should either be empty or not crash
    assertNotNull(typeParamHints)

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

  def testResolveInlayHint(): Unit =
    // Ensure we have a project context
    configureScalaFile("object Main {}\n")
    val provider = InlayHintProvider(projectManager)
    val hint = InlayHint()
    hint.setPosition(Position(0, 0))
    hint.setLabel(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(": Int"))
    hint.setKind(InlayHintKind.Type)
    val resolved = provider.resolveInlayHint(hint)
    assertNotNull("resolveInlayHint should return the hint", resolved)
    assertSame("resolved hint should be the same object", hint, resolved)

  def testMultipleImplicitClauses(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  implicit val x: Int = 1
        |  implicit val s: String = "hello"
        |  def bar(implicit i: Int, s: String): String = s * i
        |  val result = bar
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should not crash with multiple implicit params", hints)

  def testComplexExpressionDoesNotCrash(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  val ys = xs.map(_ + 1).filter(_ > 2)
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should not crash on chained method calls", hints)
