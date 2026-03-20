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
