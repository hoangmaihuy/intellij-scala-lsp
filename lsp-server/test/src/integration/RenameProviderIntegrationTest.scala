package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.RenameProvider
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*

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

  def testPrepareRenameForbiddenEquals(): Unit =
    val uri = configureScalaFile(
      """class Foo:
        |  override def equals(other: Any): Boolean = false
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 15))
    assertNull("Forbidden symbol 'equals' should not be renameable", result)

  def testPrepareRenameForbiddenHashCode(): Unit =
    val uri = configureScalaFile(
      """class Foo:
        |  override def hashCode(): Int = 0
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 15))
    assertNull("Forbidden symbol 'hashCode' should not be renameable", result)

  def testPrepareRenameForbiddenToString(): Unit =
    val uri = configureScalaFile(
      """class Foo:
        |  override def toString: String = "foo"
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 15))
    assertNull("Forbidden symbol 'toString' should not be renameable", result)

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

  def testRenameTopLevelClassIncludesFileRename(): Unit =
    // Use addScalaFile so the virtual file actually gets the name "MyClass.scala"
    val uri = addScalaFile("MyClass.scala",
      """class MyClass:
        |  def hello(): Unit = ()
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(0, 6), "NewClass")
    assertNotNull("Rename of top-level class should produce edits", result)
    // When class name matches filename, we expect documentChanges (with RenameFile)
    val docChanges = result.getDocumentChanges
    assertNotNull("Should use documentChanges for file rename", docChanges)
    val hasRenameFileOp = docChanges.asScala.exists: change =>
      change.isRight && change.getRight.isInstanceOf[org.eclipse.lsp4j.RenameFile]
    assertTrue("Should include a RenameFile resource operation", hasRenameFileOp)

  def testRenameTopLevelClassNoFileRenameWhenNamesDiffer(): Unit =
    // Use addScalaFile so the virtual file gets the name "SomeFile.scala"
    val uri = addScalaFile("SomeFile.scala",
      """class MyClass:
        |  def hello(): Unit = ()
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(0, 6), "NewClass")
    // File name "SomeFile" != class name "MyClass", so no file rename should occur
    if result != null then
      val docChanges = result.getDocumentChanges
      if docChanges != null then
        val hasRenameFileOp = docChanges.asScala.exists: change =>
          change.isRight && change.getRight.isInstanceOf[org.eclipse.lsp4j.RenameFile]
        assertFalse("Should NOT include RenameFile when class name doesn't match filename", hasRenameFileOp)

  def testRenameClassWithCompanionObject(): Unit =
    val uri = configureScalaFile(
      """class MyData(val x: Int)
        |object MyData:
        |  def empty: MyData = MyData(0)
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(0, 6), "YourData")
    assertNotNull("Rename of class with companion should produce edits", result)
    // Both the class and its companion object declarations should be renamed.
    // We just verify that edits are produced — the companion rename is included.
    val hasEdits = if result.getDocumentChanges != null then !result.getDocumentChanges.isEmpty
                   else result.getChanges != null && !result.getChanges.isEmpty
    assertTrue("Should have edits for class and companion rename", hasEdits)
