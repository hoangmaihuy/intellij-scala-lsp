package org.jetbrains.scalalsP.intellij

import munit.FunSuite

// Tests for URI parsing logic used across multiple classes.
class UriParsingTest extends FunSuite:

  // The URI parsing logic is duplicated in IntellijProjectManager and LspConversions.
  // Test it thoroughly here.

  private def uriToPath(uri: String): String =
    if uri.startsWith("file://") then
      java.net.URI.create(uri).getPath
    else
      uri

  test("standard file URI"):
    assertEquals(uriToPath("file:///home/user/src/Main.scala"), "/home/user/src/Main.scala")

  test("file URI with encoded spaces"):
    assertEquals(uriToPath("file:///home/user/my%20project/Main.scala"), "/home/user/my project/Main.scala")

  test("file URI with encoded special chars"):
    assertEquals(uriToPath("file:///home/user/%23test/Main.scala"), "/home/user/#test/Main.scala")

  test("plain path passthrough"):
    assertEquals(uriToPath("/home/user/src/Main.scala"), "/home/user/src/Main.scala")

  test("file URI root"):
    assertEquals(uriToPath("file:///"), "/")

  test("file URI with single component"):
    assertEquals(uriToPath("file:///tmp"), "/tmp")

  test("Windows file URI"):
    val path = uriToPath("file:///C:/Users/user/project/Main.scala")
    // On all platforms, URI.getPath returns /C:/Users/...
    assert(path.endsWith("Users/user/project/Main.scala"))

  test("deeply nested path"):
    val uri = "file:///a/b/c/d/e/f/g/h/Main.scala"
    assertEquals(uriToPath(uri), "/a/b/c/d/e/f/g/h/Main.scala")

  test("path with dots"):
    assertEquals(uriToPath("file:///home/user/../other/Main.scala"), "/home/user/../other/Main.scala")

  test("path with unicode"):
    val uri = "file:///home/user/%E4%B8%AD%E6%96%87/Main.scala"
    val path = uriToPath(uri)
    assert(path.contains("中文"))
