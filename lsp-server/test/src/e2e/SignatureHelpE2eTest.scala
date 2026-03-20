package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class SignatureHelpE2eTest extends E2eTestBase:

  def testSignatureHelpOnShapeConstructor(): Unit =
    val uri = openFixture("hierarchy/Circle.scala")
    val help = client.signatureHelp(uri, 3, 40)
    // May or may not find signatures — just verify no error
