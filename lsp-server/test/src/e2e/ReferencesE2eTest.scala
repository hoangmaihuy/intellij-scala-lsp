package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class ReferencesE2eTest extends E2eTestBase:

  def testReferencesToTrait(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("hierarchy/ShapeOps.scala")
    openFixture("service/Repository.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/ShapeService.scala")
    openFixture("Main.scala")
    // line 2: "sealed trait Shape:" — "Shape" at col 13
    val result = client.references(uri, line = 2, char = 13)
    assertTrue(s"Should find multiple references to Shape, found ${result.size}",
      result.size >= 2)

  def testReferencesToAbstractMethod(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeService.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    // line 3: "def area: Double" — "area" at col 6
    val result = client.references(uri, line = 3, char = 6)
    assertTrue(s"Should find references to area, found ${result.size}",
      result.size >= 1)
