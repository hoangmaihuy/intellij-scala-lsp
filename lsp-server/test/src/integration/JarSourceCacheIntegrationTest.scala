package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.DefinitionProvider
import org.junit.Assert.*

class JarSourceCacheIntegrationTest extends ScalaLspTestBase:

  private def getDefinition(uri: String, pos: Position) =
    DefinitionProvider(projectManager).getDefinition(uri, pos)

  def testExternalDefinitionReturnsCachedFileUri(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs: List[Int] = List(1, 2, 3)
        |""".stripMargin
    )
    // "List" at line 1, col 10 — should resolve to scala.collection.immutable.List
    val result = getDefinition(uri, positionAt(1, 10))
    if result.nonEmpty then
      val defUri = result.head.getUri
      // Should be a file:// URI (cached decompiled source), not jar:
      assertTrue(
        s"Definition of stdlib type should use file:// URI (cached), got: $defUri",
        defUri.startsWith("file://")
      )
      assertFalse(
        s"Definition should NOT use jar: URI, got: $defUri",
        defUri.startsWith("jar:")
      )

  def testExternalDefinitionCachedFileExists(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs: List[Int] = List(1, 2, 3)
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(1, 10))
    if result.nonEmpty then
      val defUri = result.head.getUri
      if defUri.startsWith("file://") then
        val path = java.nio.file.Path.of(java.net.URI.create(defUri).getPath)
        assertTrue(
          s"Cached file should exist on disk: $path",
          java.nio.file.Files.exists(path)
        )
        val content = java.nio.file.Files.readString(path)
        assertTrue(
          "Cached file should have non-empty content",
          content.nonEmpty
        )

  def testExternalDefinitionContainsRealSource(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs: List[Int] = List(1, 2, 3)
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(1, 10))
    if result.nonEmpty then
      val defUri = result.head.getUri
      if defUri.startsWith("file://") then
        val path = java.nio.file.Path.of(java.net.URI.create(defUri).getPath)
        val content = java.nio.file.Files.readString(path)
        // Real source should contain Scaladoc or copyright comments
        // Decompiled stubs typically lack these
        assertTrue(
          "Source should contain real Scala source (not just decompiled stub). Content starts with: " +
            content.take(200),
          content.contains("@author") || content.contains("Copyright") ||
            content.contains("Licensed") || content.contains("scala.collection") ||
            content.length > 1000 // Real source files are substantial
        )

  def testLocalDefinitionStillUsesFileUri(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(2, 12))
    assertFalse("Should find definition of x", result.isEmpty)
    assertTrue(
      "Local definition should use file:// URI",
      result.head.getUri.startsWith("file://")
    )
    assertFalse(
      "Local definition should not point to cache dir",
      result.head.getUri.contains(".cache/intellij-scala-lsp/sources")
    )
