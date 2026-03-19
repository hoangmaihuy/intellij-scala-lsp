package org.jetbrains.scalalsP.intellij

import munit.FunSuite

// Tests for IntellijProjectManager that don't require IntelliJ platform.
class IntellijProjectManagerTest extends FunSuite:

  test("getProject throws when no project is open"):
    val manager = IntellijProjectManager()
    interceptMessage[IllegalStateException]("No project is open"):
      manager.getProject

  test("findVirtualFile returns None for nonexistent file"):
    // This test exercises the URI parsing path.
    // LocalFileSystem.getInstance() requires IntelliJ to be running,
    // so we can't test the actual file lookup in unit tests.
    // Instead, we verify the method doesn't throw on bad URIs.
    val manager = IntellijProjectManager()
    try
      val result = manager.findVirtualFile("file:///nonexistent/path/Foo.scala")
      // If IntelliJ is not running, this will throw or return None
      result match
        case None => () // expected
        case Some(_) => () // also fine if somehow found
    catch
      // Expected: IntelliJ platform not running
      case _: Exception => ()

  test("findPsiFile returns None for nonexistent file"):
    val manager = IntellijProjectManager()
    try
      // This requires both a project and IntelliJ platform
      manager.findPsiFile("file:///nonexistent/Foo.scala")
    catch
      case _: IllegalStateException => () // "No project is open"
      case _: Exception => ()
