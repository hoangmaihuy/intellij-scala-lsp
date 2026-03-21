package org.jetbrains.scalalsP.integration

import com.google.gson.JsonObject
import org.eclipse.lsp4j.{CodeLens, Range, Position}
import org.jetbrains.scalalsP.intellij.{CodeLensProvider, SuperMethodCodeLens}
import org.junit.Assert.*

class CodeLensProviderIntegrationTest extends ScalaLspTestBase:

  private def getProvider =
    CodeLensProvider(projectManager, List(SuperMethodCodeLens()))

  def testSuperMethodLens(): Unit =
    val uri = configureScalaFile(
      """trait Base {
        |  def doWork(): Unit
        |}
        |
        |class Impl extends Base {
        |  override def doWork(): Unit = println("done")
        |}
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue(s"Should have at least one code lens for overriding method, got: ${lenses.length}",
      lenses.nonEmpty)

  def testLensDataContainsContributorIdAndTitle(): Unit =
    val uri = configureScalaFile(
      """trait Base {
        |  def doWork(): Unit
        |}
        |
        |class Impl extends Base {
        |  override def doWork(): Unit = println("done")
        |}
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Should have at least one lens", lenses.nonEmpty)
    lenses.foreach: lens =>
      val data = lens.getData
      assertNotNull("Lens data must not be null", data)
      val obj = data.asInstanceOf[JsonObject]
      val contributorId = obj.get("contributorId")
      assertNotNull("Lens data must contain 'contributorId'", contributorId)
      assertFalse("contributorId must be non-empty", contributorId.getAsString.isBlank)
      val title = obj.get("title")
      assertNotNull("Lens data must contain 'title'", title)
      assertFalse("title must be non-empty", title.getAsString.isBlank)

  def testNoLensForNonOverride(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def plainMethod(): Unit = println("plain")
        |}
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Plain method should produce no code lenses", lenses.isEmpty)

  def testResolvedLensHasCommandNameScalaGotoLocation(): Unit =
    val uri = configureScalaFile(
      """trait Animal {
        |  def sound(): String
        |}
        |
        |class Dog extends Animal {
        |  override def sound(): String = "woof"
        |}
        |""".stripMargin
    )
    val provider = getProvider
    val lenses = provider.getCodeLenses(uri)
    assertTrue("Should have at least one lens", lenses.nonEmpty)

    val resolved = provider.resolveCodeLens(lenses.head)
    assertNotNull("Resolved lens must not be null", resolved)
    // The resolved lens must have a command when the target URI is non-empty
    val data = resolved.getData.asInstanceOf[JsonObject]
    val targetUri = Option(data.get("targetUri")).map(_.getAsString).getOrElse("")
    if targetUri.nonEmpty then
      val command = resolved.getCommand
      assertNotNull("Resolved lens with non-empty targetUri must have a command", command)
      assertEquals("Command name must be 'scala.gotoLocation'", "scala.gotoLocation", command.getCommand)

  def testResolvedLensCommandArgumentsIncludeUri(): Unit =
    val uri = configureScalaFile(
      """trait Animal {
        |  def sound(): String
        |}
        |
        |class Dog extends Animal {
        |  override def sound(): String = "woof"
        |}
        |""".stripMargin
    )
    val provider = getProvider
    val lenses = provider.getCodeLenses(uri)
    assertTrue("Should have at least one lens", lenses.nonEmpty)

    val resolved = provider.resolveCodeLens(lenses.head)
    val data = resolved.getData.asInstanceOf[JsonObject]
    val targetUri = Option(data.get("targetUri")).map(_.getAsString).getOrElse("")
    if targetUri.nonEmpty then
      val command = resolved.getCommand
      assertNotNull("Command must not be null when target URI is set", command)
      val args = command.getArguments
      assertNotNull("Command arguments must not be null", args)
      assertFalse("Command arguments must not be empty", args.isEmpty)
      val firstArg = args.get(0).toString
      assertTrue(
        s"First command argument must be a URI (starts with 'file://' or similar), got: $firstArg",
        firstArg.startsWith("file://") || firstArg.startsWith("\"file://")
      )

  def testMultipleOverridesProduceSeparateLenses(): Unit =
    val uri = configureScalaFile(
      """trait Shape {
        |  def area(): Double
        |  def perimeter(): Double
        |}
        |
        |class Square(side: Double) extends Shape {
        |  override def area(): Double = side * side
        |  override def perimeter(): Double = 4 * side
        |}
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Should have lenses for both overriding methods",
      lenses.length >= 2)

  def testEmptyFileNoLenses(): Unit =
    val uri = configureScalaFile("// empty\n")
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Empty file should have no lenses", lenses.isEmpty)

  def testLensRangeIsAtMethodNameLine(): Unit =
    val uri = configureScalaFile(
      """trait Base {
        |  def foo(): Int
        |}
        |
        |class Child extends Base {
        |  override def foo(): Int = 42
        |}
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Should have at least one lens", lenses.nonEmpty)
    val lens = lenses.head
    assertNotNull("Lens range must not be null", lens.getRange)
    assertEquals("Lens must be on line 5 (override is on line 5, 0-based)",
      5, lens.getRange.getStart.getLine)
