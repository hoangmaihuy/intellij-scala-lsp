package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class TypeDefinitionE2eTest extends E2eTestBase:

  def testTypeDefinitionNavigatesToType(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    // line 4: "class ShapeService(repo: ShapeRepository):" — "repo" at col 23
    val result = client.typeDefinition(uri, line = 4, char = 23)
    if result.nonEmpty then
      assertTrue("Type definition should point to ShapeRepository",
        result.head.getUri.contains("ShapeRepository"))
