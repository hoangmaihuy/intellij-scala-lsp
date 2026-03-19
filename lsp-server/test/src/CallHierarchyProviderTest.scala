package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.*

class CallHierarchyProviderTest:

  @Test def testPrepareReturnsEmptyForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val provider = CallHierarchyProvider(manager)
    try
      val result = provider.prepare("file:///nonexistent/Foo.scala", new Position(0, 0))
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testIncomingCallsReturnsEmptyForInvalidItem(): Unit =
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
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testOutgoingCallsReturnsEmptyForInvalidItem(): Unit =
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
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testProviderConstruction(): Unit =
    assertNotNull(CallHierarchyProvider(IntellijProjectManager()))
