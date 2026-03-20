package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class FoldingRangeE2eTest extends E2eTestBase:

  def testFoldingRangesForClass(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    val ranges = client.foldingRanges(uri)
    assertFalse("Should return folding ranges", ranges.isEmpty)

  def testFoldingRangesForTrait(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val ranges = client.foldingRanges(uri)
    assertFalse("Should return folding ranges for trait", ranges.isEmpty)
