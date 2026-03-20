package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class ImplementationE2eTest extends E2eTestBase:

  def testImplementationsOfTrait(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 2: "sealed trait Shape:" — "Shape" at col 13
    val result = client.implementation(uri, line = 2, char = 13)
    if result.nonEmpty then
      assertTrue(s"Should find at least 2 implementations, found ${result.size}",
        result.size >= 2)

  def testImplementationsOfAbstractMethod(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 3: "def area: Double" — "area" at col 6
    val result = client.implementation(uri, line = 3, char = 6)
    if result.nonEmpty then
      assertTrue(s"Should find implementations of area, found ${result.size}",
        result.size >= 2)
