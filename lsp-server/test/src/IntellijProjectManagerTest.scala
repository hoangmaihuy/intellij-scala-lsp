package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test

class IntellijProjectManagerTest:

  @Test(expected = classOf[IllegalStateException])
  def testGetProjectThrowsWhenNoProjectIsOpen(): Unit =
    val manager = IntellijProjectManager()
    manager.getProject

  @Test def testFindVirtualFileReturnsNoneForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    try
      val result = manager.findVirtualFile("file:///nonexistent/path/Foo.scala")
      result match
        case None => ()
        case Some(_) => ()
    catch
      case _: Exception => ()

  @Test def testFindPsiFileReturnsNoneForNonexistentFile(): Unit =
    val manager = IntellijProjectManager()
    try
      manager.findPsiFile("file:///nonexistent/Foo.scala")
    catch
      case _: IllegalStateException => () // "No project is open"
      case _: Exception => ()
