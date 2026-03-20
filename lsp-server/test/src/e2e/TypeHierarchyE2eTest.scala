package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class TypeHierarchyE2eTest extends E2eTestBase:

  def testPrepareTypeHierarchy(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 2: "sealed trait Shape:" — "Shape" at col 13
    val items = client.prepareTypeHierarchy(uri, line = 2, char = 13)
    assertFalse("Should prepare type hierarchy for Shape", items.isEmpty)
    assertEquals("Shape", items.head.getName)

  def testSubtypesOfShape(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    val items = client.prepareTypeHierarchy(uri, line = 2, char = 13)
    if items.nonEmpty then
      val subs = client.subtypes(items.head)
      assertTrue(s"Should find subtypes, found ${subs.size}", subs.size >= 2)
      val names = subs.map(_.getName).toSet
      assertTrue("Should include Circle", names.contains("Circle"))
      assertTrue("Should include Rectangle", names.contains("Rectangle"))

  def testSupertypesOfCircle(): Unit =
    val uri = openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Shape.scala")
    // line 2: "case class Circle(..." — "Circle" at col 11
    val items = client.prepareTypeHierarchy(uri, line = 2, char = 11)
    if items.nonEmpty then
      val supers = client.supertypes(items.head)
      assertTrue(s"Should find supertypes of Circle", supers.nonEmpty)
      assertTrue("Should include Shape", supers.exists(_.getName == "Shape"))
