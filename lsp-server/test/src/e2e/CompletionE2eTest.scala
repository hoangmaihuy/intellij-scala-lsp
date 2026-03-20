package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class CompletionE2eTest extends E2eTestBase:

  def testMemberCompletion(): Unit =
    openFixture("hierarchy/Shape.scala")
    openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Rectangle.scala")
    openFixture("service/Repository.scala")
    openFixture("service/ShapeRepository.scala")
    val uri = openFixture("service/ShapeService.scala")
    // line 5: "... repo.getAll..." — after "repo." at col 31
    val items = client.completion(uri, line = 5, char = 31)
    assertTrue(s"Should return completions, got ${items.size}", items.nonEmpty)
