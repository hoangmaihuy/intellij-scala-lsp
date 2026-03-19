package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{Position, SelectionRange}
import org.jetbrains.scalalsP.intellij.SelectionRangeProvider
import org.junit.Assert.*

class SelectionRangeProviderIntegrationTest extends ScalaLspTestBase:

  private def getSelectionRanges(uri: String, positions: Seq[Position]) =
    SelectionRangeProvider(projectManager).getSelectionRanges(uri, positions)

  def testSelectionRangeHierarchy(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Int =
        |    val x = 42
        |    x
        |""".stripMargin
    )
    val result = getSelectionRanges(uri, Seq(positionAt(2, 12)))
    assertFalse("Should return selection ranges", result.isEmpty)
    val sr = result.head
    assertNotNull("Should have a range", sr)
    assertNotNull("Should have a range object", sr.getRange)
    var current = sr
    while current.getParent != null do
      val parent = current.getParent
      assertTrue("Parent start should be <= child start",
        parent.getRange.getStart.getLine < current.getRange.getStart.getLine ||
        (parent.getRange.getStart.getLine == current.getRange.getStart.getLine &&
         parent.getRange.getStart.getCharacter <= current.getRange.getStart.getCharacter))
      current = parent

  def testMultiplePositions(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1
        |  val y = 2
        |""".stripMargin
    )
    val positions = Seq(positionAt(1, 10), positionAt(2, 10))
    val result = getSelectionRanges(uri, positions)
    assertEquals("Should return one range per position", 2, result.size)
    assertNotNull(result(0))
    assertNotNull(result(1))

  def testSelectionAtFileLevel(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val result = getSelectionRanges(uri, Seq(positionAt(0, 0)))
    assertFalse("Should return ranges even at file start", result.isEmpty)

  def testParentRangeIsWider(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def compute(a: Int, b: Int): Int =
        |    val sum = a + b
        |    sum * 2
        |""".stripMargin
    )
    val result = getSelectionRanges(uri, Seq(positionAt(2, 14)))
    assertFalse(result.isEmpty)
    var current = result.head
    while current.getParent != null do
      val parent = current.getParent
      val childSize = (current.getRange.getEnd.getLine - current.getRange.getStart.getLine) * 1000 +
        (current.getRange.getEnd.getCharacter - current.getRange.getStart.getCharacter)
      val parentSize = (parent.getRange.getEnd.getLine - parent.getRange.getStart.getLine) * 1000 +
        (parent.getRange.getEnd.getCharacter - parent.getRange.getStart.getCharacter)
      assertTrue("Parent range should cover more or equal text", parentSize >= childSize)
      current = parent
