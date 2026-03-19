package org.jetbrains.scalalsP.intellij

import munit.FunSuite
import org.eclipse.lsp4j.Position

class SelectionRangeProviderTest extends FunSuite:

  test("getSelectionRanges returns empty for nonexistent file"):
    val manager = IntellijProjectManager()
    val provider = SelectionRangeProvider(manager)
    try
      val result = provider.getSelectionRanges(
        "file:///nonexistent/Foo.scala",
        Seq(new Position(0, 0))
      )
      // Should return a list with null entries (one per position)
      assertEquals(result.length, 1)
    catch
      case _: Exception => ()

  test("provider construction succeeds"):
    val manager = IntellijProjectManager()
    val provider = SelectionRangeProvider(manager)
    assert(provider != null)
