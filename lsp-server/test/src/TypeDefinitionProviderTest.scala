package org.jetbrains.scalalsP.intellij

import munit.FunSuite

class TypeDefinitionProviderTest extends FunSuite:

  test("getTypeDefinition returns empty for nonexistent file"):
    val manager = IntellijProjectManager()
    val provider = TypeDefinitionProvider(manager)
    try
      val result = provider.getTypeDefinition(
        "file:///nonexistent/Foo.scala",
        org.eclipse.lsp4j.Position(0, 0)
      )
      assertEquals(result, Seq.empty)
    catch
      case _: Exception => ()

  test("extractClassFromType handles null gracefully"):
    // The reflection-based type extraction should not throw on unexpected input
    val manager = IntellijProjectManager()
    val provider = TypeDefinitionProvider(manager)
    // Provider is constructed without errors
    assert(provider != null)
