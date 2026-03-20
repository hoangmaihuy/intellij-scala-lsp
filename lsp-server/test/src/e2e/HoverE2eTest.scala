package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class HoverE2eTest extends E2eTestBase:

  def testHoverOnTraitName(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    // line 2: "sealed trait Shape:" — "Shape" at col 13
    val hover = client.hover(uri, line = 2, char = 13)
    assertTrue("Should return hover for Shape", hover.isDefined)

  def testHoverOnMethod(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    // line 5: "def totalArea: Double = ..." — "totalArea" at col 6
    val hover = client.hover(uri, line = 5, char = 6)
    assertTrue("Should return hover for totalArea", hover.isDefined)

  def testHoverOnExtensionMethod(): Unit =
    val uri = openFixture("hierarchy/ShapeOps.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 8: "def scaled(factor: Double): Shape = ..." — "scaled" at col 6
    val hover = client.hover(uri, line = 8, char = 6)
    assertTrue("Should return hover for extension method", hover.isDefined)
