package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class InlayHintE2eTest extends E2eTestBase:

  def testInlayHintsOnValDeclarations(): Unit =
    val uri = openFixture("Main.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    openFixture("service/ShapeService.scala")
    openFixture("external/CatsUsage.scala")
    val hints = client.inlayHints(uri, startLine = 0, endLine = 15)
    // Main test: doesn't crash
