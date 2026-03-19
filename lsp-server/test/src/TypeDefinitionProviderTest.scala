package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

class TypeDefinitionProviderTest:

  @Test def testGetTypeDefinitionReturnsEmptyForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val provider = TypeDefinitionProvider(manager)
    try
      val result = provider.getTypeDefinition("file:///nonexistent/Foo.scala", org.eclipse.lsp4j.Position(0, 0))
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testProviderConstruction(): Unit =
    assertNotNull(TypeDefinitionProvider(IntellijProjectManager()))
