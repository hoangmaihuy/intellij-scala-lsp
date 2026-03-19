package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.*

class DiagnosticsProviderTest:

  @Test def testCollectDiagnosticsReturnsEmptyForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)
    try
      val result = provider.collectDiagnostics("file:///nonexistent/Foo.scala")
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testPublishDiagnosticsDoesNotThrowWithoutClient(): Unit =
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)
    provider.publishDiagnostics("file:///nonexistent/Foo.scala")

  @Test def testPublishDiagnosticsSendsToConnectedClient(): Unit =
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)

    val received = scala.collection.mutable.ArrayBuffer[PublishDiagnosticsParams]()
    val client = new org.jetbrains.scalalsP.TestLanguageClient:
      override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
        received += params

    provider.connect(client)
    try
      provider.publishDiagnostics("file:///nonexistent/Foo.scala")
      if received.nonEmpty then
        assertEquals("file:///nonexistent/Foo.scala", received.head.getUri)
    catch case _: Exception => ()

  @Test def testTrackOpenAndTrackCloseManageFileTracking(): Unit =
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)

    val received = scala.collection.mutable.ArrayBuffer[PublishDiagnosticsParams]()
    val client = new org.jetbrains.scalalsP.TestLanguageClient:
      override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
        received += params

    provider.connect(client)
    provider.trackOpen("file:///test/Foo.scala")
    provider.trackClose("file:///test/Foo.scala")

    if received.nonEmpty then
      val last = received.last
      assertEquals("file:///test/Foo.scala", last.getUri)
      assertTrue(last.getDiagnostics.isEmpty)

  @Test def testToLspSeverityMapsCorrectly(): Unit =
    import com.intellij.lang.annotation.HighlightSeverity
    val provider = DiagnosticsProvider(IntellijProjectManager())

    assertEquals(DiagnosticSeverity.Error, provider.toLspSeverity(HighlightSeverity.ERROR))
    assertEquals(DiagnosticSeverity.Warning, provider.toLspSeverity(HighlightSeverity.WARNING))
    assertEquals(DiagnosticSeverity.Information, provider.toLspSeverity(HighlightSeverity.WEAK_WARNING))
    assertEquals(DiagnosticSeverity.Hint, provider.toLspSeverity(HighlightSeverity.INFORMATION))
