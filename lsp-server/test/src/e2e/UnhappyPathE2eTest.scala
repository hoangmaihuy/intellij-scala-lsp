package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class UnhappyPathE2eTest extends E2eTestBase:

  def testNonexistentFileUri(): Unit =
    val fakeUri = "file:///nonexistent/path/Foo.scala"
    val result = client.definition(fakeUri, line = 0, char = 0)
    assertTrue("Should return empty for nonexistent file", result.isEmpty)

  def testPositionBeyondEof(): Unit =
    val uri = openFixture("hierarchy/Circle.scala")
    val result = client.definition(uri, line = 1000, char = 0)
    assertTrue("Should return empty for position beyond EOF", result.isEmpty)

  def testMalformedUri(): Unit =
    val result = client.definition("not-a-valid-uri", line = 0, char = 0)
    assertTrue("Should return empty for malformed URI", result.isEmpty)

  def testHoverOnEmptyPosition(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val hover = client.hover(uri, line = 1, char = 0)
    // Should not throw — empty line

  def testDidCloseAndThenRequest(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    client.closeFile(uri)
    val result = client.definition(uri, line = 2, char = 13)
    // Should not throw

  def testChangeFileWithBadContent(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    client.changeFile(uri, "this is not valid scala {{{")
    val result = client.definition(uri, line = 0, char = 0)
    val hover = client.hover(uri, line = 0, char = 5)
    // Just verify no exceptions
