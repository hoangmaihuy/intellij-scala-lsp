package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class CodeActionE2eTest extends E2eTestBase:

  def testCodeActionsAvailable(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    val actions = client.codeActions(uri, 4, 0, 14, 0)
    // Verify it doesn't crash
