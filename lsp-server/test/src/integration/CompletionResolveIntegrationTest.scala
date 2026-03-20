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

  def testResolveMethodWithParametersGetsSnippet(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    // Find a method item that has parameters (e.g., "map", "filter", "foldLeft")
    val methodItems = items.filter(i => i.getLabel != null && Seq("map", "filter", "foreach", "flatMap").contains(i.getLabel))
    if methodItems.nonEmpty then
      val resolved = provider.resolveCompletion(methodItems.head)
      // If the resolved item has snippet format, verify it contains a placeholder ($)
      val format = resolved.getInsertTextFormat
      val insertText = resolved.getInsertText
      if format == org.eclipse.lsp4j.InsertTextFormat.Snippet then
        assertNotNull("Snippet insert text should not be null", insertText)
        assertTrue("Snippet insert text should contain $ placeholder", insertText.contains("$"))
        assertTrue("Snippet insert text should contain method name", insertText.startsWith(resolved.getLabel))

  def testResolveZeroParamMethodGetsPlainText(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    // Find a zero-param method item (e.g., "size", "isEmpty", "reverse")
    val zeroParamItems = items.filter(i => i.getLabel != null && Seq("size", "isEmpty", "reverse", "length").contains(i.getLabel))
    if zeroParamItems.nonEmpty then
      val resolved = provider.resolveCompletion(zeroParamItems.head)
      val format = resolved.getInsertTextFormat
      // Zero-param methods should not use snippet format
      assertTrue(
        "Zero-param method should use PlainText or have insert text without snippet placeholders",
        format == org.eclipse.lsp4j.InsertTextFormat.PlainText ||
          (resolved.getInsertText != null && !resolved.getInsertText.contains("$1"))
      )
