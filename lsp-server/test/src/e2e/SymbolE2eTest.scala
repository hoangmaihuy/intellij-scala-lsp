package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class SymbolE2eTest extends E2eTestBase:

  def testDocumentSymbols(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val symbols = client.documentSymbols(uri)
    assertFalse("Should return document symbols", symbols.isEmpty)

  def testDocumentSymbolsForService(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    val symbols = client.documentSymbols(uri)
    assertFalse("Should return symbols for ShapeService", symbols.isEmpty)

  def testWorkspaceSymbol(): Unit =
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("service/ShapeService.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    val symbols = client.workspaceSymbol("ShapeService")
    assertTrue(s"Should find ShapeService, found ${symbols.size}", symbols.nonEmpty)

  def testWorkspaceSymbolFindsMethod(): Unit =
    openFixture("service/ShapeService.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    // Search for a method by simple name
    val symbols = client.workspaceSymbol("totalArea")
    assertTrue(s"Should find method 'totalArea', got: ${symbols.map(s => s"${s.getContainerName}.${s.getName}").mkString(", ")}",
      symbols.exists(_.getName == "totalArea"))

  def testWorkspaceSymbolMethodHasContainer(): Unit =
    openFixture("service/ShapeService.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    // Verify method has some container info (package at minimum).
    // Full class-level container (e.g., "service.ShapeService") requires Scala 3
    // language level to be configured, which test projects don't have.
    val symbols = client.workspaceSymbol("totalArea")
    assertTrue(s"Should find method 'totalArea', got: ${symbols.map(s => s"${s.getContainerName}.${s.getName}").mkString(", ")}",
      symbols.exists(s => s.getName == "totalArea" && s.getContainerName != null))
