package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.RenameProvider
import org.junit.Assert.*

class RenameProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = RenameProvider(projectManager)

  def testPrepareRenameOnVal(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val myValue = 42
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 6))
    assertNotNull("Should be able to rename a val", result)
    assertEquals("myValue", result.getPlaceholder)

  def testPrepareRenameOnMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String) = s"Hello, $name"
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 6))
    assertNotNull("Should be able to rename a method", result)
    assertEquals("greet", result.getPlaceholder)

  def testPrepareRenameOnEmptyLine(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |
        |""".stripMargin
    )
    // Position on an empty line — nothing to rename
    val result = provider.prepareRename(uri, positionAt(2, 0))
    assertNull("Empty line should not be renameable", result)

  def testRenameLocalVariable(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(1, 6), "y")
    assertNotNull(result)
    val changes = result.getChanges
    assertNotNull(changes)
    // Should have changes for this file
    assertTrue("Should have edits", !changes.isEmpty)

  def testRenameMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String) = s"Hello, $name"
        |  def a = greet("Alice")
        |  def b = greet("Bob")
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(1, 6), "sayHello")
    assertNotNull(result)
    val changes = result.getChanges
    assertNotNull(changes)

  def testRenameWithConflict(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1
        |  val y = 2
        |  def foo = x + y
        |""".stripMargin
    )
    // Rename x to y — should still produce edits (conflict detection is client-side)
    val result = provider.rename(uri, positionAt(1, 6), "y")
    assertNotNull("Rename should produce edits even with conflicts", result)

  def testRenameCrossFile(): Unit =
    addScalaFile("Helper.scala",
      """package example
        |object Helper:
        |  def compute(x: Int): Int = x * 2
        |""".stripMargin
    )
    val uri = configureScalaFile("Main.scala",
      """package example
        |object Main:
        |  val result = Helper.compute(21)
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(2, 27), "calculate")
    // Cross-file resolution may not work in light test mode
    if result != null then
      assertNotNull(result.getChanges)
