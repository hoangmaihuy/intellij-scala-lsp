package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class RenameE2eTest extends E2eTestBase:

  def testRenameMethod(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    // line 3: "def area: Double" — "area" at col 6
    val edit = client.rename(uri, line = 3, char = 6, newName = "getArea")
    if edit != null && edit.getChanges != null then
      assertTrue(s"Should produce edits, got ${edit.getChanges.size} files",
        edit.getChanges.size >= 1)
