package org.jetbrains.scalalsP.protocol

import munit.FunSuite

class LspConversionsTest extends FunSuite:

  test("uriToPath converts file URI to path"):
    assertEquals(
      LspConversions.uriToPath("file:///home/user/project/src/Main.scala"),
      "/home/user/project/src/Main.scala"
    )

  test("uriToPath converts file URI with spaces"):
    assertEquals(
      LspConversions.uriToPath("file:///home/user/my%20project/src/Main.scala"),
      "/home/user/my project/src/Main.scala"
    )

  test("uriToPath passes through non-URI paths"):
    assertEquals(
      LspConversions.uriToPath("/home/user/project/src/Main.scala"),
      "/home/user/project/src/Main.scala"
    )

  test("pathToUri converts path to file URI"):
    assertEquals(
      LspConversions.pathToUri("/home/user/project/src/Main.scala"),
      "file:///home/user/project/src/Main.scala"
    )

  test("uriToPath and pathToUri roundtrip"):
    val path = "/home/user/project/src/Main.scala"
    assertEquals(LspConversions.uriToPath(LspConversions.pathToUri(path)), path)

  test("uriToPath handles Windows-style file URIs"):
    // Windows URI: file:///C:/Users/user/project/Main.scala
    val uri = "file:///C:/Users/user/project/Main.scala"
    val path = LspConversions.uriToPath(uri)
    assert(path.contains("Users/user/project/Main.scala"))

  test("uriToPath handles empty path"):
    assertEquals(LspConversions.uriToPath("file:///"), "/")
