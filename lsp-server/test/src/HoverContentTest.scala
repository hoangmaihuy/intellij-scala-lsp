package org.jetbrains.scalalsP.intellij

import munit.FunSuite

// Tests for hover content markdown generation.
// buildHoverContent is private[intellij], accessible from this test package.
class HoverContentTest extends FunSuite:

  private val hoverProvider = HoverProvider(IntellijProjectManager())

  private def buildHoverContent(typeInfo: Option[String], docInfo: Option[String]): String =
    hoverProvider.buildHoverContent(typeInfo, docInfo)

  test("hover with type info only"):
    val result = buildHoverContent(Some("Int"), None)
    assertEquals(result, "```scala\nInt\n```")

  test("hover with doc info only"):
    val result = buildHoverContent(None, Some("Returns the sum"))
    assertEquals(result, "Returns the sum")

  test("hover with both type and doc"):
    val result = buildHoverContent(Some("def foo: Int"), Some("Does stuff"))
    assert(result.contains("```scala\ndef foo: Int\n```"))
    assert(result.contains("Does stuff"))
    assert(result.contains("---"))

  test("hover with empty type and empty doc"):
    val result = buildHoverContent(None, None)
    assertEquals(result, "")

  test("hover strips HTML from documentation"):
    val result = buildHoverContent(None, Some("<p>Hello <b>world</b></p>"))
    assertEquals(result, "Hello world")

  test("hover with empty HTML doc produces empty"):
    val result = buildHoverContent(None, Some("<p></p>"))
    assertEquals(result, "")

  test("hover with complex type signature"):
    val result = buildHoverContent(
      Some("def map[B](f: A => B): List[B]"),
      Some("Applies f to each element")
    )
    assert(result.contains("def map[B](f: A => B): List[B]"))
    assert(result.contains("Applies f to each element"))

  test("hover separates sections with horizontal rule"):
    val result = buildHoverContent(Some("String"), Some("A string value"))
    val parts = result.split("\n\n---\n\n")
    assertEquals(parts.length, 2)

  test("hover with multiline documentation"):
    val doc = "<p>First line</p><p>Second line</p>"
    val result = buildHoverContent(Some("Int"), Some(doc))
    assert(result.contains("First line"))
    assert(result.contains("Second line"))

  test("hover doc with nested HTML tags"):
    val doc = "<div><code>val x = 1</code><br/>Some description</div>"
    val result = buildHoverContent(None, Some(doc))
    assert(result.contains("val x = 1"))
    assert(result.contains("Some description"))
    assert(!result.contains("<div>"))
    assert(!result.contains("<code>"))
