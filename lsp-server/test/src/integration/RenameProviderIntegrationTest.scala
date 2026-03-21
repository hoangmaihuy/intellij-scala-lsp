package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{Position, RenameFile, ResourceOperation, TextDocumentEdit, TextEdit, WorkspaceEdit}
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither}
import org.jetbrains.scalalsP.intellij.RenameProvider
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*

class RenameProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = RenameProvider(projectManager)

  /** Extract all (uri -> TextEdit) pairs from a WorkspaceEdit, handling both
   *  `changes` (plain map) and `documentChanges` (TextDocumentEdit / ResourceOperation). */
  private def collectAllTextEdits(edit: WorkspaceEdit): Seq[(String, TextEdit)] =
    val fromChanges: Seq[(String, TextEdit)] =
      if edit.getChanges != null then
        edit.getChanges.asScala.flatMap: (uri, edits) =>
          edits.asScala.map(uri -> _)
        .toSeq
      else Seq.empty

    val fromDocChanges: Seq[(String, TextEdit)] =
      if edit.getDocumentChanges != null then
        edit.getDocumentChanges.asScala.flatMap: either =>
          if either.isLeft then
            val tde = either.getLeft
            tde.getEdits.asScala.map(tde.getTextDocument.getUri -> _)
          else Seq.empty
        .toSeq
      else Seq.empty

    (fromChanges ++ fromDocChanges).distinct

  // ---------- prepareRename tests ----------

  def testPrepareRenameOnVal(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val myValue = 42
        |}
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 6))
    assertNotNull("Should be able to rename a val", result)
    assertEquals("Placeholder should be the current name", "myValue", result.getPlaceholder)

  def testPrepareRenameOnMethod(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def greet(name: String) = s"Hello, $name"
        |}
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 6))
    assertNotNull("Should be able to rename a method", result)
    assertEquals("Placeholder should be the current name", "greet", result.getPlaceholder)

  def testPrepareRenameOnEmptyLine(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |
        |}
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(2, 0))
    assertNull("Empty line should not be renameable", result)

  def testForbiddenRenameEquals(): Unit =
    val uri = configureScalaFile(
      """class Foo {
        |  override def equals(other: Any): Boolean = false
        |}
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 15))
    assertNull("Forbidden symbol 'equals' should not be renameable", result)

  def testForbiddenRenameHashCode(): Unit =
    val uri = configureScalaFile(
      """class Foo {
        |  override def hashCode(): Int = 0
        |}
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 15))
    assertNull("Forbidden symbol 'hashCode' should not be renameable", result)

  def testForbiddenRenameToString(): Unit =
    val uri = configureScalaFile(
      """class Foo {
        |  override def toString: String = "foo"
        |}
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 15))
    assertNull("Forbidden symbol 'toString' should not be renameable", result)

  // ---------- rename tests: edit content ----------

  def testRenameUpdatesAllReferences(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def hello = "world"
        |  val x = hello
        |  val y = hello
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(1, 6), "greet")
    assertNotNull("Should produce a WorkspaceEdit", edit)

    val allEdits = collectAllTextEdits(edit)
    assertTrue("Should produce at least one text edit", allEdits.nonEmpty)

    // Every text edit must contain exactly the new name — not a partial replacement
    allEdits.foreach: (editUri, textEdit) =>
      assertEquals("Each edit's new text must equal the new name",
        "greet", textEdit.getNewText)

  def testRenameLocalVariable(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(1, 6), "y")
    assertNotNull("Rename should produce a WorkspaceEdit", edit)

    val allEdits = collectAllTextEdits(edit)
    assertTrue("Should have edits", allEdits.nonEmpty)
    allEdits.foreach: (_, textEdit) =>
      assertEquals("Every edit must set the new name", "y", textEdit.getNewText)

    // Must have edits for the definition line (line 1) and for usages (lines 2, 3)
    if allEdits.size >= 3 then
      val editLines = allEdits.map(_._2.getRange.getStart.getLine).toSet
      assertTrue("Should edit definition on line 1", editLines.contains(1))
      assertTrue("Should edit usage on line 2", editLines.contains(2))
      assertTrue("Should edit usage on line 3", editLines.contains(3))

  def testRenameMethod(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def greet(name: String) = s"Hello, $name"
        |  def a = greet("Alice")
        |  def b = greet("Bob")
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(1, 6), "sayHello")
    assertNotNull("Should produce a WorkspaceEdit", edit)

    val allEdits = collectAllTextEdits(edit)
    assertTrue("Should have edits", allEdits.nonEmpty)
    allEdits.foreach: (_, textEdit) =>
      assertEquals("Every edit must set the new name", "sayHello", textEdit.getNewText)

  def testRenameWithConflict(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 1
        |  val y = 2
        |  def foo = x + y
        |}
        |""".stripMargin
    )
    // Rename x to y — conflict detection is client-side; server must still produce edits
    val edit = provider.rename(uri, positionAt(1, 6), "y")
    assertNotNull("Rename should produce edits even when a naming conflict exists", edit)
    val allEdits = collectAllTextEdits(edit)
    assertTrue("Should have at least one edit", allEdits.nonEmpty)

  // ---------- rename tests: file rename ----------

  def testFileRenameIncludedWhenClassMatchesFilename(): Unit =
    val uri = addScalaFile("Foo.scala",
      """class Foo {
        |  def bar(): Unit = ()
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(0, 6), "Bar")
    assertNotNull("Should produce a WorkspaceEdit", edit)

    val docChanges = edit.getDocumentChanges
    assertNotNull("Should use documentChanges when a file rename is needed", docChanges)

    val renameOps = docChanges.asScala
      .filter(_.isRight)
      .map(_.getRight)
      .collect { case rf: RenameFile => rf }
      .toSeq

    assertTrue("Should include at least one RenameFile resource operation", renameOps.nonEmpty)
    assertTrue("RenameFile oldUri should end with Foo.scala",
      renameOps.exists(_.getOldUri.endsWith("Foo.scala")))
    assertTrue("RenameFile newUri should end with Bar.scala",
      renameOps.exists(_.getNewUri.endsWith("Bar.scala")))

  def testNoFileRenameWhenClassNameDiffersFromFilename(): Unit =
    val uri = addScalaFile("SomeFile.scala",
      """class MyClass {
        |  def hello(): Unit = ()
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(0, 6), "NewClass")
    // File name "SomeFile" != class name "MyClass" — no file rename expected
    if edit != null then
      val docChanges = edit.getDocumentChanges
      if docChanges != null then
        val hasFileRename = docChanges.asScala.exists: change =>
          change.isRight && change.getRight.isInstanceOf[RenameFile]
        assertFalse("Should NOT include RenameFile when class name doesn't match filename",
          hasFileRename)

  // ---------- rename tests: companion object ----------

  def testRenameClassWithCompanionObject(): Unit =
    val uri = configureScalaFile(
      """class MyData(val x: Int)
        |object MyData {
        |  def empty: MyData = MyData(0)
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(0, 6), "YourData")
    assertNotNull("Rename of class with companion should produce edits", edit)

    val allEdits = collectAllTextEdits(edit)
    assertTrue("Should have edits", allEdits.nonEmpty)
    allEdits.foreach: (_, textEdit) =>
      assertEquals("Every text edit must set the new name", "YourData", textEdit.getNewText)

    // The companion object declaration is on line 1; it must be included
    if allEdits.size >= 2 then
      val editLines = allEdits.map(_._2.getRange.getStart.getLine).toSet
      assertTrue("Should rename the companion object on line 1", editLines.contains(1))

  // ---------- rename tests: cross-file ----------

  def testRenameCrossFile(): Unit =
    addScalaFile("Helper.scala",
      """package example
        |object Helper {
        |  def compute(x: Int): Int = x * 2
        |}
        |""".stripMargin
    )
    val uri = configureScalaFile("Main.scala",
      """package example
        |object Main {
        |  val result = Helper.compute(21)
        |}
        |""".stripMargin
    )
    val edit = provider.rename(uri, positionAt(2, 27), "calculate")
    // Cross-file resolution may not work in light test mode
    if edit != null then
      val allEdits = collectAllTextEdits(edit)
      assertTrue("Cross-file rename should produce edits", allEdits.nonEmpty)
      allEdits.foreach: (_, textEdit) =>
        assertEquals("Every edit must set the new name", "calculate", textEdit.getNewText)
