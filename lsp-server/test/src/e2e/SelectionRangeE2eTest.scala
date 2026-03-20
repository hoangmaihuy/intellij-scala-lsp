package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class SelectionRangeE2eTest extends E2eTestBase:

  def testSelectionRangeExpands(): Unit =
    val uri = openFixture("hierarchy/ShapeOps.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 9: inside extension method body
    val ranges = client.selectionRanges(uri, List((9, 20)))
    assertFalse("Should return selection ranges", ranges.isEmpty)
    assertNotNull("Should have parent range", ranges.head.getParent)
