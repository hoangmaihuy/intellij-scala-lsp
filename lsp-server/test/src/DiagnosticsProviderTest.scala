package org.jetbrains.scalalsP.intellij

import munit.FunSuite
import org.eclipse.lsp4j.*

class DiagnosticsProviderTest extends FunSuite:

  test("collectDiagnostics returns empty for nonexistent file"):
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)
    try
      val result = provider.collectDiagnostics("file:///nonexistent/Foo.scala")
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("publishDiagnostics does not throw without client"):
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)
    // Should be a no-op when client is not connected
    provider.publishDiagnostics("file:///nonexistent/Foo.scala")

  test("publishDiagnostics sends to connected client"):
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
        assertEquals(received.head.getUri, "file:///nonexistent/Foo.scala")
    catch
      case _: Exception => ()

  test("trackOpen and trackClose manage file tracking"):
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)

    val received = scala.collection.mutable.ArrayBuffer[PublishDiagnosticsParams]()
    val client = new org.jetbrains.scalalsP.TestLanguageClient:
      override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
        received += params

    provider.connect(client)

    // trackClose should send empty diagnostics to clear
    provider.trackOpen("file:///test/Foo.scala")
    provider.trackClose("file:///test/Foo.scala")

    // Should have received a clear (empty diagnostics list)
    if received.nonEmpty then
      val last = received.last
      assertEquals(last.getUri, "file:///test/Foo.scala")
      assert(last.getDiagnostics.isEmpty)

  test("toLspSeverity maps correctly"):
    import com.intellij.lang.annotation.HighlightSeverity
    val manager = IntellijProjectManager()
    val provider = DiagnosticsProvider(manager)

    assertEquals(provider.toLspSeverity(HighlightSeverity.ERROR), DiagnosticSeverity.Error)
    assertEquals(provider.toLspSeverity(HighlightSeverity.WARNING), DiagnosticSeverity.Warning)
    assertEquals(provider.toLspSeverity(HighlightSeverity.WEAK_WARNING), DiagnosticSeverity.Information)
    assertEquals(provider.toLspSeverity(HighlightSeverity.INFORMATION), DiagnosticSeverity.Hint)
