package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

/** Tests for hover content formatting logic (HTML stripping). */
class HoverContentTest:

  /** Mirrors the HTML-stripping logic used in HoverProvider.computeHover */
  private def stripHtml(html: String): String =
    html.replaceAll("<[^>]+>", "").trim

  @Test def testStripHtmlSimpleTags(): Unit =
    assertEquals("Hello world", stripHtml("<p>Hello <b>world</b></p>"))

  @Test def testStripHtmlEmpty(): Unit =
    assertEquals("", stripHtml("<p></p>"))

  @Test def testStripHtmlNestedTags(): Unit =
    val html = "<div><code>val x = 1</code><br/>Some description</div>"
    val result = stripHtml(html)
    assertTrue(result.contains("val x = 1"))
    assertTrue(result.contains("Some description"))
    assertFalse(result.contains("<div>"))
    assertFalse(result.contains("<code>"))

  @Test def testStripHtmlPreservesPlainText(): Unit =
    assertEquals("no html here", stripHtml("no html here"))

  @Test def testStripHtmlMultipleParagraphs(): Unit =
    val result = stripHtml("<p>First line</p><p>Second line</p>")
    assertTrue(result.contains("First line"))
    assertTrue(result.contains("Second line"))
