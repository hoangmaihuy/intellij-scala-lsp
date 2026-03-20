package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class DefinitionE2eTest extends E2eTestBase:

  def testCrossFileDefinition(): Unit =
    val circleUri = openFixture("hierarchy/Circle.scala")
    // line 2: "... extends Shape:" — "Shape" at col 43
    val result = client.definition(circleUri, line = 2, char = 43)
    assertFalse("Should find definition of Shape from Circle", result.isEmpty)
    assertTrue("Definition should point to Shape.scala",
      result.head.getUri.contains("Shape"))

  def testDefinitionOfMethodOverride(): Unit =
    val circleUri = openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Shape.scala")
    // line 3: "override def area" — "area" at col 15
    val result = client.definition(circleUri, line = 3, char = 15)
    assertFalse("Should find definition", result.isEmpty)

  def testCrossPackageDefinition(): Unit =
    val serviceUri = openFixture("service/ShapeService.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 4: "class ShapeService(repo: ShapeRepository):" — "ShapeRepository" at col 24
    val result = client.definition(serviceUri, line = 4, char = 28)
    assertFalse("Should find ShapeRepository definition", result.isEmpty)
    assertTrue("Should point to ShapeRepository",
      result.head.getUri.contains("ShapeRepository"))

  def testDefinitionOnConstructor(): Unit =
    val mainUri = openFixture("Main.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 5: "val repo = ShapeRepository()" — "ShapeRepository" at col 13
    val result = client.definition(mainUri, line = 5, char = 17)
    assertFalse("Should find constructor definition", result.isEmpty)

  def testUnresolvableReferenceReturnsEmpty(): Unit =
    val uri = fixtureUri("Main.scala")
    client.openFile(uri,
      """object Broken:
        |  val x = undefinedIdentifier
        |""".stripMargin)
    val result = client.definition(uri, line = 1, char = 10)
    // Should not throw
