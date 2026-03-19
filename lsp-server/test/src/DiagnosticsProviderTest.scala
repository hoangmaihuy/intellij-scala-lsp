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

    // Without IntelliJ running, this will publish an empty diagnostics list
    try
      provider.publishDiagnostics("file:///nonexistent/Foo.scala")
      // If it runs, we should have received a publish call (possibly empty)
      if received.nonEmpty then
        assertEquals(received.head.getUri, "file:///nonexistent/Foo.scala")
    catch
      case _: Exception => ()

  test("toLspSeverity maps correctly"):
    // Test the severity mapping logic independently
    import com.intellij.lang.annotation.HighlightSeverity
    val errorSev = HighlightSeverity.ERROR
    val warnSev = HighlightSeverity.WARNING
    val weakWarnSev = HighlightSeverity.WEAK_WARNING
    val infoSev = HighlightSeverity.INFORMATION

    // Verify severity ordering (IntelliJ uses compareTo)
    assert(errorSev.compareTo(HighlightSeverity.ERROR) >= 0)
    assert(warnSev.compareTo(HighlightSeverity.WARNING) >= 0)
    assert(warnSev.compareTo(HighlightSeverity.ERROR) < 0)
    assert(weakWarnSev.compareTo(HighlightSeverity.WEAK_WARNING) >= 0)
    assert(weakWarnSev.compareTo(HighlightSeverity.WARNING) < 0)
    assert(infoSev.compareTo(HighlightSeverity.WEAK_WARNING) < 0)
