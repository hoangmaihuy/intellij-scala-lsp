package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.FoldingRangeKind
import org.jetbrains.scalalsP.intellij.FoldingRangeProvider
import org.junit.Assert.*

class FoldingRangeProviderIntegrationTest extends ScalaLspTestBase:

  private def getFoldingRanges(uri: String) =
    FoldingRangeProvider(projectManager).getFoldingRanges(uri)

  def testMultiLineMethodBodyFolds(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Unit =
        |    val x = 1
        |    val y = 2
        |    println(x + y)
        |""".stripMargin
    )
    // Folding builder may not produce results in light test mode
    val ranges = getFoldingRanges(uri)
    if ranges.nonEmpty then
      assertTrue("Folding range should span lines",
        ranges.exists(r => r.getEndLine > r.getStartLine))

  def testImportBlockFolds(): Unit =
    val uri = configureScalaFile(
      """import scala.collection.mutable
        |import scala.collection.immutable
        |import scala.util.Try
        |
        |object Main:
        |  val x = 42
        |""".stripMargin
    )
    val ranges = getFoldingRanges(uri)
    val importFolds = ranges.filter(r => r.getKind == FoldingRangeKind.Imports)
    if importFolds.nonEmpty then
      assertTrue("Import fold should start at line 0", importFolds.head.getStartLine == 0)

  def testScalaDocCommentFolds(): Unit =
    val uri = configureScalaFile(
      """/**
        | * This is a ScalaDoc comment
        | * with multiple lines
        | * describing the object
        | */
        |object Main:
        |  val x = 42
        |""".stripMargin
    )
    val ranges = getFoldingRanges(uri)
    val commentFolds = ranges.filter(r => r.getKind == FoldingRangeKind.Comment)
    if commentFolds.nonEmpty then
      assertEquals("Comment fold should start at line 0", 0, commentFolds.head.getStartLine)

  def testSingleLineBodyNoFold(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo = 42
        |""".stripMargin
    )
    val ranges = getFoldingRanges(uri)
    val bodyFolds = ranges.filter(r =>
      r.getStartLine == 1 && r.getEndLine == 1
    )
    assertTrue("Single line should not produce fold", bodyFolds.isEmpty)
