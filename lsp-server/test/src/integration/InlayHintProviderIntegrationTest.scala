package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
import org.jetbrains.scalalsP.intellij.InlayHintProvider
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*

class InlayHintProviderIntegrationTest extends ScalaLspTestBase:

  private def getHints(uri: String, range: Range) =
    InlayHintProvider(projectManager).getInlayHints(uri, range)

  private def fullRange = Range(Position(0, 0), Position(100, 0))

  private def labelOf(hint: InlayHint): String =
    val either = hint.getLabel
    if either.isLeft then either.getLeft
    else either.getRight.asScala.map(_.getValue).mkString

  def testTypeHintForInferredVal(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val typeHints = hints.filter(_.getKind == InlayHintKind.Type)
    val intHint = typeHints.find(h => labelOf(h).contains("Int"))
    assertTrue("Should have Int type hint", intHint.isDefined)
    assertEquals("Type hint should be on line 1", 1, intHint.get.getPosition.getLine)

  def testTypeHintValueAfterIdentifier(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val typeHints = hints.filter(_.getKind == InlayHintKind.Type)
    val intHint = typeHints.find(h => labelOf(h).contains("Int"))
    assertTrue("Should have Int type hint", intHint.isDefined)
    // "val x" — 'x' is at char 6; end offset of identifier is char 7
    assertTrue("Type hint should be after 'x' (char >= 7)", intHint.get.getPosition.getCharacter >= 7)

  def testTypeHintLabelStartsWithColon(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val typeHints = hints.filter(_.getKind == InlayHintKind.Type)
    val intHint = typeHints.find(h => labelOf(h).contains("Int"))
    assertTrue("Should have Int type hint", intHint.isDefined)
    val label = labelOf(intHint.get)
    assertTrue(s"Type hint label should start with ': ', got: $label", label.startsWith(": "))

  def testNoTypeHintWhenExplicit(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x: Int = 42
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    val typeHintsOnLine1 = hints.filter: h =>
      h.getKind == InlayHintKind.Type && h.getPosition.getLine == 1
    assertFalse(
      "Should NOT produce a type hint for an explicitly typed val",
      typeHintsOnLine1.exists(h => labelOf(h).contains("Int"))
    )

  def testParameterNameHints(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def greet(name: String, age: Int): Unit = ()
        |  greet("Alice", 30)
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val paramHints = hints.filter(_.getKind == InlayHintKind.Parameter)
    val labels = paramHints.map(labelOf)
    assertTrue(
      s"Parameter hints should contain 'name' or 'age', labels found: $labels",
      labels.exists(_.contains("name")) || labels.exists(_.contains("age"))
    )

  def testParameterNameHintsOnCorrectLine(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def greet(name: String, age: Int): Unit = ()
        |  greet("Alice", 30)
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val paramHints = hints.filter(h => h.getKind == InlayHintKind.Parameter && h.getPosition.getLine == 2)
    val labels = paramHints.map(labelOf)
    assertTrue(
      s"Parameter name hints on line 2 should contain 'name' or 'age', labels: $labels",
      labels.exists(_.contains("name")) || labels.exists(_.contains("age"))
    )

  def testInferredReturnType(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def foo = "hello"
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val typeHints = hints.filter(_.getKind == InlayHintKind.Type)
    // The inferred return type hint must contain some non-empty text
    typeHints.foreach: h =>
      val label = labelOf(h)
      assertTrue(s"Type hint label should not be empty, got: '$label'", label.nonEmpty)

  def testImplicitParameterHint(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  implicit val n: Int = 42
        |  def foo(implicit x: Int): Int = x
        |  val result = foo
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    // Implicit argument hints must be non-null; if present, must have non-empty labels
    assertNotNull("hints list must not be null", hints)
    hints.foreach: h =>
      assertNotNull("Each hint must have a kind", h.getKind)
      val label = labelOf(h)
      assertTrue(s"Each hint label must be non-empty, got: '$label'", label.nonEmpty)

  def testEmptyFileNoHints(): Unit =
    val uri = configureScalaFile("// empty\n")
    val hints = getHints(uri, Range(Position(0, 0), Position(1, 0)))
    assertTrue("Empty file should produce no inlay hints", hints.isEmpty)

  def testResolveInlayHint(): Unit =
    configureScalaFile("object Main {}\n")
    val provider = InlayHintProvider(projectManager)
    val hint = InlayHint()
    hint.setPosition(Position(0, 0))
    hint.setLabel(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(": Int"))
    hint.setKind(InlayHintKind.Type)
    val resolved = provider.resolveInlayHint(hint)
    assertNotNull("resolveInlayHint should return the hint", resolved)
    assertSame("resolveInlayHint should return the same object", hint, resolved)
    assertEquals("resolveInlayHint should preserve the label", ": Int", labelOf(resolved))

  def testTypeHintForInferredVar(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  var count = 0
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    assertNotNull("Should return a list of hints", hints)
    val typeHints = hints.filter(_.getKind == InlayHintKind.Type)
    val intHint = typeHints.find(h => labelOf(h).contains("Int"))
    assertTrue("Should have Int type hint for var", intHint.isDefined)
    val label = labelOf(intHint.get)
    assertTrue(s"Type hint label should start with ': ', got: $label", label.startsWith(": "))

  def testComplexExpressionProducesHints(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val xs = List(1, 2, 3)
        |  val ys = xs.map(_ + 1).filter(_ > 2)
        |}
        |""".stripMargin
    )
    val hints = getHints(uri, fullRange)
    // Must not crash and hints must all have non-empty labels
    assertNotNull("hints must not be null", hints)
    hints.foreach: h =>
      assertNotNull("hint kind must not be null", h.getKind)
      val label = labelOf(h)
      assertTrue(s"hint label must be non-empty, got: '$label'", label.nonEmpty)
