package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

class HoverContentTest:

  private val hoverProvider = HoverProvider(IntellijProjectManager())

  private def buildHoverContent(typeInfo: Option[String], docInfo: Option[String]): String =
    hoverProvider.buildHoverContent(typeInfo, docInfo)

  @Test def testHoverWithTypeInfoOnly(): Unit =
    assertEquals("```scala\nInt\n```", buildHoverContent(Some("Int"), None))

  @Test def testHoverWithDocInfoOnly(): Unit =
    assertEquals("Returns the sum", buildHoverContent(None, Some("Returns the sum")))

  @Test def testHoverWithBothTypeAndDoc(): Unit =
    val result = buildHoverContent(Some("def foo: Int"), Some("Does stuff"))
    assertTrue(result.contains("```scala\ndef foo: Int\n```"))
    assertTrue(result.contains("Does stuff"))
    assertTrue(result.contains("---"))

  @Test def testHoverWithEmptyTypeAndEmptyDoc(): Unit =
    assertEquals("", buildHoverContent(None, None))

  @Test def testHoverStripsHtmlFromDocumentation(): Unit =
    assertEquals("Hello world", buildHoverContent(None, Some("<p>Hello <b>world</b></p>")))

  @Test def testHoverWithEmptyHtmlDocProducesEmpty(): Unit =
    assertEquals("", buildHoverContent(None, Some("<p></p>")))

  @Test def testHoverWithComplexTypeSignature(): Unit =
    val result = buildHoverContent(
      Some("def map[B](f: A => B): List[B]"),
      Some("Applies f to each element")
    )
    assertTrue(result.contains("def map[B](f: A => B): List[B]"))
    assertTrue(result.contains("Applies f to each element"))

  @Test def testHoverSeparatesSectionsWithHorizontalRule(): Unit =
    val result = buildHoverContent(Some("String"), Some("A string value"))
    val parts = result.split("\n\n---\n\n")
    assertEquals(2, parts.length)

  @Test def testHoverWithMultilineDocumentation(): Unit =
    val doc = "<p>First line</p><p>Second line</p>"
    val result = buildHoverContent(Some("Int"), Some(doc))
    assertTrue(result.contains("First line"))
    assertTrue(result.contains("Second line"))

  @Test def testHoverDocWithNestedHtmlTags(): Unit =
    val doc = "<div><code>val x = 1</code><br/>Some description</div>"
    val result = buildHoverContent(None, Some(doc))
    assertTrue(result.contains("val x = 1"))
    assertTrue(result.contains("Some description"))
    assertFalse(result.contains("<div>"))
    assertFalse(result.contains("<code>"))
