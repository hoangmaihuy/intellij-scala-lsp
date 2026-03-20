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

  def testCodeActionResolveRoundTrip(): Unit =
    val uri = openFixture("service/ShapeService.scala")
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/ShapeRepository.scala")
    openFixture("service/Repository.scala")
    val actions = client.codeActions(uri, 4, 0, 14, 0)
    // Light test framework may not produce highlighting/intentions.
    // When actions ARE available, assert the full resolve round-trip works.
    assertFalse(
      "codeActions round-trip was NOT tested because the light test framework " +
        "produced no actions. If this starts failing, the fixture needs updating.",
      actions.isEmpty && sys.env.contains("CI")
    )
    if actions.nonEmpty then
      val firstAction = actions.head
      assertNotNull("Action should have data", firstAction.getData)
      val resolved = client.resolveCodeAction(firstAction)
      assertNotNull("Resolved action should not be null", resolved)
      // The edit may or may not be present depending on the fix,
      // but the resolve should not throw
      assertNotNull("Resolved action should have title", resolved.getTitle)
