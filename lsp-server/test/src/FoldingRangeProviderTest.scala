package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

class FoldingRangeProviderTest:

  @Test def testGetFoldingRangesReturnsEmptyForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val provider = FoldingRangeProvider(manager)
    try
      val result = provider.getFoldingRanges("file:///nonexistent/Foo.scala")
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testProviderConstruction(): Unit =
    assertNotNull(FoldingRangeProvider(IntellijProjectManager()))
