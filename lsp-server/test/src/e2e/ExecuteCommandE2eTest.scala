package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class ExecuteCommandE2eTest extends E2eTestBase:

  def testReformatCommand(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    val result = client.executeCommand("scala.reformat",
      java.util.List.of(uri.asInstanceOf[AnyRef]))
    // Should not throw
