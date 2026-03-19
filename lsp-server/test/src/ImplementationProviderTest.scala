package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

class ImplementationProviderTest:

  @Test def testGetImplementationsReturnsEmptyForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val provider = ImplementationProvider(manager)
    try
      val result = provider.getImplementations("file:///nonexistent/Foo.scala", org.eclipse.lsp4j.Position(0, 0))
      assertEquals(Seq.empty, result)
    catch case _: Exception => ()

  @Test def testProviderConstruction(): Unit =
    assertNotNull(ImplementationProvider(IntellijProjectManager()))
