package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class FormattingE2eTest extends E2eTestBase:

  def testFormattingViaLsp(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val result = client.formatting(uri)
    assertNotNull("Formatting should return non-null", result)

  def testRangeFormattingViaLsp(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val range = new org.eclipse.lsp4j.Range(
      new org.eclipse.lsp4j.Position(0, 0),
      new org.eclipse.lsp4j.Position(5, 0)
    )
    val result = client.rangeFormatting(uri, range)
    assertNotNull("Range formatting should return non-null", result)
