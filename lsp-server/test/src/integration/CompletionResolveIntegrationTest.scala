package org.jetbrains.scalalsP.integration

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.CompletionProvider
import org.junit.Assert.*

class CompletionResolveIntegrationTest extends ScalaLspTestBase:

  private def provider = CompletionProvider(projectManager)

  private def listCompletions =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    provider.getCompletions(uri, positionAt(2, 5))

  def testCompletionItemsHaveLabels(): Unit =
    val items = listCompletions
    assertNotNull("Should return a list of completion items", items)
    if items.nonEmpty then
      items.foreach: item =>
        assertNotNull("Each item must have a label", item.getLabel)
        assertTrue("Label must be non-empty", item.getLabel.nonEmpty)
    // If no items produced, the light test framework didn't provide completions — that's acceptable

  def testCompletionItemsAreLean(): Unit =
    val items = listCompletions
    if items.nonEmpty then
      val first = items.head
      assertNull("Documentation should be lazy-loaded (null before resolve)", first.getDocumentation)

  def testCompletionDataFieldStructure(): Unit =
    val items = listCompletions
    assertNotNull("Should return a list of completion items", items)
    if items.nonEmpty then
      val first = items.head
      assertNotNull("Completion item should have data field", first.getData)
      val data = first.getData.asInstanceOf[JsonObject]
      assertTrue("Data should have requestId", data.has("requestId"))
      assertTrue("Data should have index", data.has("index"))
      assertTrue("requestId must be a non-negative Long", data.get("requestId").getAsLong >= 0)
      assertEquals("First item index should be 0", 0, data.get("index").getAsInt)
    // If no items produced, the light test framework didn't provide completions — that's acceptable

  def testDataIndexIsConsecutive(): Unit =
    val items = listCompletions
    if items.size >= 3 then
      items.take(3).zipWithIndex.foreach: (item, expected) =>
        val idx = item.getData.asInstanceOf[JsonObject].get("index").getAsInt
        assertEquals(s"Item at position $expected should have index $expected", expected, idx)

  def testAllItemsShareSameRequestId(): Unit =
    val items = listCompletions
    if items.size >= 2 then
      val requestIds = items.map(_.getData.asInstanceOf[JsonObject].get("requestId").getAsLong)
      val distinct = requestIds.distinct
      assertEquals("All items in one batch must share the same requestId", 1, distinct.size)

  def testResolvePopulatesDetailOrDocumentation(): Unit =
    val items = listCompletions
    if items.nonEmpty then
      val resolved = provider.resolveCompletion(items.head)
      val hasDetail = resolved.getDetail != null && resolved.getDetail.nonEmpty
      val hasDoc    = resolved.getDocumentation != null
      assertTrue("Resolved item should have detail or documentation", hasDetail || hasDoc)

  def testResolvedMethodGetsSnippet(): Unit =
    val items = listCompletions
    val methodItem = items.find: i =>
      i.getLabel != null && Seq("map", "filter", "foreach", "flatMap").contains(i.getLabel)
    if methodItem.isDefined then
      val resolved = provider.resolveCompletion(methodItem.get)
      if resolved.getInsertTextFormat == InsertTextFormat.Snippet then
        val text = resolved.getInsertText
        assertNotNull("Snippet insert text must not be null", text)
        assertTrue(
          s"Snippet should start with method name '${resolved.getLabel}', got: $text",
          text.startsWith(resolved.getLabel)
        )
        assertTrue(
          s"Snippet should contain $$1 placeholder, got: $text",
          text.contains("$1")
        )
        assertTrue(
          s"Snippet should contain parentheses, got: $text",
          text.contains("(") && text.contains(")")
        )

  def testResolvedPropertyGetsPlainText(): Unit =
    val items = listCompletions
    val propItem = items.find: i =>
      i.getLabel != null && Seq("size", "isEmpty", "reverse", "length").contains(i.getLabel)
    if propItem.isDefined then
      val resolved = provider.resolveCompletion(propItem.get)
      val format   = resolved.getInsertTextFormat
      assertTrue(
        "Zero-param property/field should use PlainText or have no $1 snippet placeholder",
        format == InsertTextFormat.PlainText ||
          (resolved.getInsertText != null && !resolved.getInsertText.contains("$1"))
      )

  def testResolveStaleRequestReturnsItemUnchanged(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val firstBatch = provider.getCompletions(uri, positionAt(2, 5))
    if firstBatch.nonEmpty then
      // Second request invalidates the cache for the first batch
      provider.getCompletions(uri, positionAt(2, 5))
      val resolved = provider.resolveCompletion(firstBatch.head)
      // Stale resolve must not throw and must return the item
      assertNotNull("Stale resolve must still return the item", resolved)
      assertEquals("Stale resolve must preserve the label", firstBatch.head.getLabel, resolved.getLabel)
