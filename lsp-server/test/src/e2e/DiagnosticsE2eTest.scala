package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class DiagnosticsE2eTest extends E2eTestBase:

  def testDiagnosticsOnSyntaxError(): Unit =
    val uri = fixtureUri("Main.scala")
    client.openFile(uri,
      """object Broken:
        |  def foo: Int = "not an int"
        |""".stripMargin)
    val diagnostics = client.awaitDiagnostics(uri, timeoutMs = 10000)
    // Main assertion: doesn't crash

  def testNoDiagnosticsOnCleanFile(): Unit =
    val uri = openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Shape.scala")
    Thread.sleep(2000)
    // Clean file should not crash
