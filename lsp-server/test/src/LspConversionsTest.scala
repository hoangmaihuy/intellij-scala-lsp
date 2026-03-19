package org.jetbrains.scalalsP.protocol

import org.junit.Assert.*
import org.junit.Test

class LspConversionsTest:

  @Test def testUriToPathConvertsFileUri(): Unit =
    assertEquals(
      "/home/user/project/src/Main.scala",
      LspConversions.uriToPath("file:///home/user/project/src/Main.scala")
    )

  @Test def testUriToPathConvertsFileUriWithSpaces(): Unit =
    assertEquals(
      "/home/user/my project/src/Main.scala",
      LspConversions.uriToPath("file:///home/user/my%20project/src/Main.scala")
    )

  @Test def testUriToPathPassesThroughNonUriPaths(): Unit =
    assertEquals(
      "/home/user/project/src/Main.scala",
      LspConversions.uriToPath("/home/user/project/src/Main.scala")
    )

  @Test def testPathToUriConvertsPathToFileUri(): Unit =
    assertEquals(
      "file:///home/user/project/src/Main.scala",
      LspConversions.pathToUri("/home/user/project/src/Main.scala")
    )

  @Test def testUriToPathAndPathToUriRoundtrip(): Unit =
    val path = "/home/user/project/src/Main.scala"
    assertEquals(path, LspConversions.uriToPath(LspConversions.pathToUri(path)))

  @Test def testUriToPathHandlesWindowsStyleFileUris(): Unit =
    val uri = "file:///C:/Users/user/project/Main.scala"
    val path = LspConversions.uriToPath(uri)
    assertTrue(path.contains("Users/user/project/Main.scala"))

  @Test def testUriToPathHandlesEmptyPath(): Unit =
    assertEquals("/", LspConversions.uriToPath("file:///"))
