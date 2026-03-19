package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

class UriParsingTest:

  private def uriToPath(uri: String): String =
    if uri.startsWith("file://") then
      java.net.URI.create(uri).getPath
    else
      uri

  @Test def testStandardFileUri(): Unit =
    assertEquals("/home/user/src/Main.scala", uriToPath("file:///home/user/src/Main.scala"))

  @Test def testFileUriWithEncodedSpaces(): Unit =
    assertEquals("/home/user/my project/Main.scala", uriToPath("file:///home/user/my%20project/Main.scala"))

  @Test def testFileUriWithEncodedSpecialChars(): Unit =
    assertEquals("/home/user/#test/Main.scala", uriToPath("file:///home/user/%23test/Main.scala"))

  @Test def testPlainPathPassthrough(): Unit =
    assertEquals("/home/user/src/Main.scala", uriToPath("/home/user/src/Main.scala"))

  @Test def testFileUriRoot(): Unit =
    assertEquals("/", uriToPath("file:///"))

  @Test def testFileUriWithSingleComponent(): Unit =
    assertEquals("/tmp", uriToPath("file:///tmp"))

  @Test def testWindowsFileUri(): Unit =
    val path = uriToPath("file:///C:/Users/user/project/Main.scala")
    assertTrue(path.endsWith("Users/user/project/Main.scala"))

  @Test def testDeeplyNestedPath(): Unit =
    assertEquals("/a/b/c/d/e/f/g/h/Main.scala", uriToPath("file:///a/b/c/d/e/f/g/h/Main.scala"))

  @Test def testPathWithDots(): Unit =
    assertEquals("/home/user/../other/Main.scala", uriToPath("file:///home/user/../other/Main.scala"))

  @Test def testPathWithUnicode(): Unit =
    val path = uriToPath("file:///home/user/%E4%B8%AD%E6%96%87/Main.scala")
    assertTrue(path.contains("中文"))
