package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class SemanticTokensE2eTest extends E2eTestBase:

  def testSemanticTokensOnFixture(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val tokens = client.semanticTokensFull(uri)
    assertNotNull("Should return semantic tokens", tokens)
    assertNotNull("Should have data", tokens.getData)
