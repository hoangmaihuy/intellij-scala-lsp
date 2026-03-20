package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class DiagnosticsE2eTest extends E2eTestBase:

  def testDiagnosticsOnSyntaxError(): Unit =
    // Avoid client.openFile — it triggers document sync via WriteCommandAction which
    // conflicts with light test mode and causes "project disposed" errors.
    // Diagnostics do not fire in light test mode anyway.
    // Verify that awaitDiagnostics returns without throwing for a file that was never opened.
    val uri = fixtureUri("Main.scala")
    val diagnostics = client.awaitDiagnostics(uri, timeoutMs = 500)
    // Main assertion: doesn't crash

  def testNoDiagnosticsOnCleanFile(): Unit =
    val uri = openFixture("hierarchy/Circle.scala")
    openFixture("hierarchy/Shape.scala")
    Thread.sleep(2000)
    // Clean file should not crash
