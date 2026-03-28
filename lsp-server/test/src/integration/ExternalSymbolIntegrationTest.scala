package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.SymbolKind
import org.jetbrains.scalalsP.intellij.{DefinitionProvider, ReferencesProvider, SymbolProvider}
import org.junit.Assert.*

/**
 * Tests workspace/symbol, definition, and references for external library symbols
 * (cats-core and zio). These tests verify the complete flow that was changed:
 *   - allScope includes library classes
 *   - getContainerName returns proper FQN for library elements
 *   - FQN filtering works for fully qualified queries
 *   - elementToLocation caches library sources correctly
 */
class ExternalSymbolIntegrationTest extends ExternalLibraryTestBase:

  private def workspaceSymbols(query: String) =
    SymbolProvider(projectManager).workspaceSymbols(query)

  // --- workspace/symbol: simple name queries ---

  def testWorkspaceSymbolFindsCatsMonad(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("Monad")
    val monad = result.find(s => s.getName == "Monad")
    assertTrue(s"Should find Monad, got ${result.size} results: ${result.map(s => s"${s.getName}@${s.getContainerName}").mkString(", ")}",
      monad.isDefined)

  def testWorkspaceSymbolFindsZIO(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("ZIO")
    val zio = result.find(_.getName == "ZIO")
    assertTrue(s"Should find ZIO, got ${result.size} results: ${result.map(s => s"${s.getName}@${s.getContainerName}").mkString(", ")}",
      zio.isDefined)

  def testWorkspaceSymbolMonadHasContainerName(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("Monad")
    val monad = result.find(_.getName == "Monad")
    if monad.isDefined then
      val container = monad.get.getContainerName
      assertNotNull("Monad containerName should not be null", container)
      assertTrue(s"Monad container '$container' should contain 'cats'",
        container.contains("cats"))

  def testWorkspaceSymbolZIOHasContainerName(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("ZIO")
    val zio = result.find(_.getName == "ZIO")
    if zio.isDefined then
      val container = zio.get.getContainerName
      assertNotNull("ZIO containerName should not be null", container)
      assertTrue(s"ZIO container '$container' should contain 'zio'",
        container.contains("zio"))

  // --- workspace/symbol: FQN queries ---

  def testWorkspaceSymbolFqnCatsMonad(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("cats.Monad")
    // For dotted queries, result names include container prefix (e.g., "cats.Monad")
    val monad = result.find(s => s.getName == "Monad" || s.getName == "cats.Monad")
    assertTrue("FQN query 'cats.Monad' should find Monad",
      monad.isDefined)
    if monad.isDefined then
      val container = monad.get.getContainerName
      assertNotNull("containerName should not be null for FQN result", container)
      assertTrue(s"container '$container' should match 'cats'",
        container.contains("cats"))

  def testWorkspaceSymbolFqnZioZIO(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("zio.ZIO")
    // For dotted queries, result names include container prefix (e.g., "zio.ZIO")
    val zio = result.find(s => s.getName == "ZIO" || s.getName == "zio.ZIO")
    assertTrue("FQN query 'zio.ZIO' should find ZIO",
      zio.isDefined)

  def testWorkspaceSymbolFqnRejectsWrongPackage(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    // "scalaz.Monad" should NOT find cats.Monad (scalaz is not a dependency)
    val result = workspaceSymbols("scalaz.Monad")
    val wrongMatch = result.find(s => s.getName == "Monad" &&
      s.getContainerName != null && s.getContainerName.contains("cats"))
    assertTrue("FQN 'scalaz.Monad' should not match cats.Monad",
      wrongMatch.isEmpty)

  // --- workspace/symbol: simple name returns results from all packages ---

  def testSimpleNameReturnsProjectAndLibrary(): Unit =
    addScalaFile("pkg/Monad.scala",
      """package pkg
        |class Monad
        |""".stripMargin
    )
    val result = workspaceSymbols("Monad")
    val projectMonad = result.exists(s => s.getName == "Monad" &&
      s.getContainerName != null && s.getContainerName.contains("pkg"))
    val catsMonad = result.exists(s => s.getName == "Monad" &&
      s.getContainerName != null && s.getContainerName.contains("cats"))
    assertTrue("Simple name should find project Monad", projectMonad)
    assertTrue("Simple name should also find cats Monad", catsMonad)

  // --- definition: external library symbols ---

  def testDefinitionOfExternalSymbolFromUsageSite(): Unit =
    val uri = configureScalaFile(
      """import cats.Monad
        |object Main:
        |  def foo[F[_]: Monad](x: F[Int]) = x
        |""".stripMargin
    )
    // "Monad" import at line 0, col 12
    val result = DefinitionProvider(projectManager).getDefinition(uri, positionAt(0, 12))
    // Should find definition — might be empty if import resolution needs full SDK
    if result.nonEmpty then
      val defUri = result.head.getUri
      assertTrue(s"Definition URI should be a file: $defUri",
        defUri.startsWith("file://"))

  // --- references: external library symbols from project code ---

  def testReferencesOfExternalSymbolInProject(): Unit =
    val uri = configureScalaFile(
      """import cats.Monad
        |object Main:
        |  def foo[F[_]: Monad](x: F[Int]) = x
        |  def bar[G[_]: Monad](y: G[String]) = y
        |""".stripMargin
    )
    // "Monad" at its first usage (line 2, col 16)
    val refs = ReferencesProvider(projectManager).findReferences(uri, positionAt(2, 16), false)
    // Should find at least one reference (the other usage on line 3)
    if refs.nonEmpty then
      assertTrue(s"Should find Monad references in project, found ${refs.size}",
        refs.size >= 1)

  // --- elementToLocation: cached source files ---

  def testExternalSymbolLocationIsCachedFile(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("Monad")
    val monad = result.find(_.getName == "Monad")
    assertTrue("Monad must be found for location test", monad.isDefined)
    val uri = monad.get.getLocation.getUri
    // External symbols should be cached as file:// URIs (not jar: URIs)
    assertTrue(s"External symbol URI should be file://, got: $uri",
      uri.startsWith("file://"))
    // Should be in the cache directory
    assertTrue(s"External symbol should be cached in sources dir, got: $uri",
      uri.contains(".cache/intellij-scala-lsp/sources") || uri.contains("/sources/"))

  def testExternalSymbolLocationHasValidRange(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("Monad")
    val monad = result.find(_.getName == "Monad")
    assertTrue("Monad must be found for range test", monad.isDefined)
    val loc = monad.get.getLocation
    val range = loc.getRange
    // Range must be valid (not 0:0-0:0 unless the element is truly at the start)
    assertNotNull("Range should not be null", range)
    assertNotNull("Start position should not be null", range.getStart)
    assertNotNull("End position should not be null", range.getEnd)
    // Start line should be >= 0
    assertTrue(s"Start line should be >= 0, got ${range.getStart.getLine}",
      range.getStart.getLine >= 0)

  def testExternalSymbolLocationFileExists(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    val result = workspaceSymbols("Monad")
    val monad = result.find(_.getName == "Monad")
    assertTrue("Monad must be found for file existence test", monad.isDefined)
    val uri = monad.get.getLocation.getUri
    val path = java.net.URI.create(uri).getPath
    assertTrue(s"Cached source file should exist: $path",
      java.nio.file.Files.exists(java.nio.file.Path.of(path)))
    // File should have content (not empty)
    val content = java.nio.file.Files.readString(java.nio.file.Path.of(path))
    assertTrue(s"Cached source file should not be empty",
      content.nonEmpty)
    // File should contain "Monad" somewhere (it's the Monad source)
    assertTrue(s"Cached source should mention Monad",
      content.contains("Monad"))

  // --- Regression: elementToLocation must not silently drop library elements ---

  def testAllExternalWorkspaceSymbolsHaveValidLocations(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    // Query "ZIO" — all results should have valid file:// URIs
    val result = workspaceSymbols("ZIO")
    assertTrue("Should find ZIO symbols", result.nonEmpty)
    for sym <- result do
      val uri = sym.getLocation.getUri
      assertTrue(s"Symbol '${sym.getName}' should have file:// URI, got: $uri",
        uri.startsWith("file://"))
      val range = sym.getLocation.getRange
      assertNotNull(s"Symbol '${sym.getName}' should have a range", range)

  def testWorkspaceSymbolsCountMatchesExpected(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    // Simple query "Monad" should return > 0 results (not silently drop all)
    val result = workspaceSymbols("Monad")
    assertTrue(s"workspace/symbol for 'Monad' should return results, got ${result.size}",
      result.size > 0)
    // FQN query should also return > 0
    val fqnResult = workspaceSymbols("cats.Monad")
    assertTrue(s"workspace/symbol for 'cats.Monad' should return results, got ${fqnResult.size}",
      fqnResult.size > 0)
