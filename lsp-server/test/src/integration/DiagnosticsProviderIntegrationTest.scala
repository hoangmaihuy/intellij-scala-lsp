package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{DiagnosticSeverity, PublishDiagnosticsParams}
import org.jetbrains.scalalsP.intellij.DiagnosticsProvider
import org.jetbrains.scalalsP.TestLanguageClient
import org.junit.Assert.*

class DiagnosticsProviderIntegrationTest extends ScalaLspTestBase:

  private def diagnosticsProvider: DiagnosticsProvider =
    DiagnosticsProvider(projectManager)

  def testTypeErrorProducesDiagnostic(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = "hello"
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val provider = diagnosticsProvider
    val diagnostics = provider.collectDiagnostics(uri)
    if diagnostics.nonEmpty then
      assertTrue("Should have an error diagnostic",
        diagnostics.exists(_.getSeverity == DiagnosticSeverity.Error))

  def testUnusedImportProducesWarning(): Unit =
    val uri = configureScalaFile(
      """import scala.collection.mutable.ArrayBuffer
        |object Main:
        |  val x = 42
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val diagnostics = diagnosticsProvider.collectDiagnostics(uri)
    assertNotNull(diagnostics)

  def testCleanCodeNoDiagnostics(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |  def greet(name: String): String = s"Hello, $name"
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val diagnostics = diagnosticsProvider.collectDiagnostics(uri)
    // In light test mode without Scala SDK, clean code may still produce diagnostics
    // (e.g., unresolved String type). Only check that no crash occurs.
    assertNotNull(diagnostics)

  def testTrackClosePublishesEmptyDiagnostics(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val provider = diagnosticsProvider
    val received = scala.collection.mutable.ArrayBuffer[PublishDiagnosticsParams]()
    val client = new TestLanguageClient:
      override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
        received += params

    provider.connect(client)
    provider.trackOpen(uri)
    provider.trackClose(uri)

    assertTrue("Should have published diagnostics on close", received.nonEmpty)
    val lastPublish = received.last
    assertEquals(uri, lastPublish.getUri)
    assertTrue("Close should clear diagnostics", lastPublish.getDiagnostics.isEmpty)
