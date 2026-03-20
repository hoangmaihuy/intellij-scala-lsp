package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class DocumentLinkE2eTest extends E2eTestBase:

  def testDocumentLinksOnFixture(): Unit =
    val uri = openFixture("hierarchy/ShapeOps.scala")
    val links = client.documentLinks(uri)
    assertNotNull(links)
