package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{CodeLens, Range, Position}
import org.jetbrains.scalalsP.intellij.{CodeLensProvider, SuperMethodCodeLens}
import org.junit.Assert.*

class CodeLensProviderIntegrationTest extends ScalaLspTestBase:

  private def getProvider =
    CodeLensProvider(projectManager, List(SuperMethodCodeLens()))

  def testSuperMethodLens(): Unit =
    val uri = configureScalaFile(
      """trait Base:
        |  def doWork(): Unit
        |
        |class Impl extends Base:
        |  override def doWork(): Unit = println("done")
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    // The overriding doWork should have a code lens
    assertTrue(s"Should have at least one code lens for overriding method, got: ${lenses.length}",
      lenses.nonEmpty)
    // Lens should reference 'doWork'
    assertTrue("Lens title should mention 'doWork'",
      lenses.exists: lens =>
        val data = lens.getData
        data != null && data.toString.contains("doWork")
    )

  def testNoLensForNonOverride(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def plainMethod(): Unit = println("plain")
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    // A plain method with no super should produce no lenses
    assertTrue("Plain method should produce no code lenses", lenses.isEmpty)

  def testResolvedLensHasCommand(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def sound(): String
        |
        |class Dog extends Animal:
        |  override def sound(): String = "woof"
        |""".stripMargin
    )
    val provider = getProvider
    val lenses = provider.getCodeLenses(uri)
    assertTrue("Should have at least one lens", lenses.nonEmpty)

    // Resolve the first lens
    val resolved = provider.resolveCodeLens(lenses.head)
    assertNotNull("Resolved lens should not be null", resolved)
    // A resolved lens that has a targetUri should have a command
    val data = resolved.getData
    if data != null then
      val dataStr = data.toString
      if dataStr.contains("targetUri") && !dataStr.contains("\"targetUri\":\"\"") then
        assertNotNull("Resolved lens with target should have a command", resolved.getCommand)

  def testMultipleOverrides(): Unit =
    val uri = configureScalaFile(
      """trait Shape:
        |  def area(): Double
        |  def perimeter(): Double
        |
        |class Square(side: Double) extends Shape:
        |  override def area(): Double = side * side
        |  override def perimeter(): Double = 4 * side
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Should have lenses for both overriding methods",
      lenses.length >= 2)

  def testEmptyFileNoLenses(): Unit =
    val uri = configureScalaFile("// empty\n")
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Empty file should have no lenses", lenses.isEmpty)

  def testLensRangeIsAtMethodName(): Unit =
    val uri = configureScalaFile(
      """trait Base:
        |  def foo(): Int
        |
        |class Child extends Base:
        |  override def foo(): Int = 42
        |""".stripMargin
    )
    val lenses = getProvider.getCodeLenses(uri)
    assertTrue("Should have at least one lens", lenses.nonEmpty)
    // The lens should be on line 4 (0-based), where the override is
    val lens = lenses.head
    assertNotNull("Lens range should not be null", lens.getRange)
    assertEquals("Lens should be on line 4", 4, lens.getRange.getStart.getLine)
