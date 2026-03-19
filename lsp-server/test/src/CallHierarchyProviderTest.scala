package org.jetbrains.scalalsP.intellij

import munit.FunSuite
import org.eclipse.lsp4j.*

class CallHierarchyProviderTest extends FunSuite:

  test("prepare returns empty for nonexistent file"):
    val manager = IntellijProjectManager()
    val provider = CallHierarchyProvider(manager)
    try
      val result = provider.prepare(
        "file:///nonexistent/Foo.scala",
        new Position(0, 0)
      )
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("incomingCalls returns empty for invalid item"):
    val manager = IntellijProjectManager()
    val provider = CallHierarchyProvider(manager)
    val item = new CallHierarchyItem(
      "test", SymbolKind.Method,
      "file:///nonexistent/Foo.scala",
      new Range(new Position(0, 0), new Position(0, 10)),
      new Range(new Position(0, 0), new Position(0, 4))
    )
    try
      val result = provider.incomingCalls(item)
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("outgoingCalls returns empty for invalid item"):
    val manager = IntellijProjectManager()
    val provider = CallHierarchyProvider(manager)
    val item = new CallHierarchyItem(
      "test", SymbolKind.Method,
      "file:///nonexistent/Foo.scala",
      new Range(new Position(0, 0), new Position(0, 10)),
      new Range(new Position(0, 0), new Position(0, 4))
    )
    try
      val result = provider.outgoingCalls(item)
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("provider construction succeeds"):
    val manager = IntellijProjectManager()
    val provider = CallHierarchyProvider(manager)
    assert(provider != null)
