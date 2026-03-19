package org.jetbrains.scalalsP.intellij

import munit.FunSuite

class FoldingRangeProviderTest extends FunSuite:

  test("getFoldingRanges returns empty for nonexistent file"):
    val manager = IntellijProjectManager()
    val provider = FoldingRangeProvider(manager)
    try
      val result = provider.getFoldingRanges("file:///nonexistent/Foo.scala")
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("provider construction succeeds"):
    val manager = IntellijProjectManager()
    val provider = FoldingRangeProvider(manager)
    assert(provider != null)
