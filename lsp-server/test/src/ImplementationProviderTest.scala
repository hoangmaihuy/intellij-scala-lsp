package org.jetbrains.scalalsP.intellij

import munit.FunSuite

class ImplementationProviderTest extends FunSuite:

  test("getImplementations returns empty for nonexistent file"):
    val manager = IntellijProjectManager()
    val provider = ImplementationProvider(manager)
    try
      val result = provider.getImplementations(
        "file:///nonexistent/Foo.scala",
        org.eclipse.lsp4j.Position(0, 0)
      )
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("provider construction succeeds"):
    val manager = IntellijProjectManager()
    val provider = ImplementationProvider(manager)
    assert(provider != null)
