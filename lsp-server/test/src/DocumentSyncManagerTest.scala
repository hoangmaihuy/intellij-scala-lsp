package org.jetbrains.scalalsP.intellij

import org.junit.Test

class DocumentSyncManagerTest:

  @Test def testDidOpenForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try sync.didOpen("file:///nonexistent/Foo.scala", "object Foo")
    catch case _: Exception => ()

  @Test def testDidChangeForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try sync.didChange("file:///nonexistent/Foo.scala", "object Foo { val x = 1 }")
    catch case _: Exception => ()

  @Test def testDidCloseForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try sync.didClose("file:///nonexistent/Foo.scala")
    catch case _: Exception => ()

  @Test def testDidSaveForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try sync.didSave("file:///nonexistent/Foo.scala")
    catch case _: Exception => ()
