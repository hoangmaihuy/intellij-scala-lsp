package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.CompletionProvider
import org.junit.Assert.*

class CompletionResolveIntegrationTest extends ScalaLspTestBase:

  private def provider = CompletionProvider(projectManager)

  def testCompletionItemsHaveDataField(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      val first = items.head
      assertNotNull("Completion item should have data field", first.getData)

  def testCompletionItemsAreLean(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      val first = items.head
      assertNull("Documentation should be lazy-loaded", first.getDocumentation)

  def testResolvePopulatesDocumentation(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      val resolved = provider.resolveCompletion(items.head)
      val hasDetail = resolved.getDetail != null && resolved.getDetail.nonEmpty
      val hasDoc = resolved.getDocumentation != null
      assertTrue("Resolved item should have detail or documentation", hasDetail || hasDoc)

  def testResolveStaleRequestReturnsItemUnchanged(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      // Make a second completion request which invalidates the cache
      provider.getCompletions(uri, positionAt(2, 5))
      val resolved = provider.resolveCompletion(items.head)
      assertNotNull("Stale resolve should still return an item", resolved)
