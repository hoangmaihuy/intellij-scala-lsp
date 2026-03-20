package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class FormattingE2eTest extends E2eTestBase:

  def testFormattingViaLsp(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val result = client.formatting(uri)
    assertNotNull("Formatting should return non-null", result)
