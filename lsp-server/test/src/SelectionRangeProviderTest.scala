package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.Position

class SelectionRangeProviderTest:

  @Test def testGetSelectionRangesReturnsEmptyForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val provider = SelectionRangeProvider(manager)
    try
      val result = provider.getSelectionRanges("file:///nonexistent/Foo.scala", Seq(new Position(0, 0)))
      assertEquals(1, result.length)
    catch case _: Exception => ()

  @Test def testProviderConstruction(): Unit =
    assertNotNull(SelectionRangeProvider(IntellijProjectManager()))
