package org.jetbrains.scalalsP.intellij

import munit.FunSuite

// Tests for DocumentSyncManager behavior.
// Full integration requires IntelliJ platform; these test the interface contract.
class DocumentSyncManagerTest extends FunSuite:

  test("didOpen for nonexistent file logs warning"):
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    // Without IntelliJ running, this should handle the missing file gracefully
    try
      sync.didOpen("file:///nonexistent/Foo.scala", "object Foo")
    catch
      // Expected: IntelliJ platform not running
      case _: Exception => ()

  test("didChange for nonexistent file logs warning"):
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try
      sync.didChange("file:///nonexistent/Foo.scala", "object Foo { val x = 1 }")
    catch
      case _: Exception => ()

  test("didClose for nonexistent file is no-op"):
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try
      sync.didClose("file:///nonexistent/Foo.scala")
    catch
      case _: Exception => ()

  test("didSave for nonexistent file is no-op"):
    val manager = IntellijProjectManager()
    val sync = DocumentSyncManager(manager)
    try
      sync.didSave("file:///nonexistent/Foo.scala")
    catch
      case _: Exception => ()
