package org.jetbrains.scalalsP.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.scalalsP.intellij.DocumentSyncManager
import org.junit.Assert.*

import java.nio.charset.StandardCharsets

class DocumentSyncManagerIntegrationTest extends ScalaLspTestBase:

  private def syncManager: DocumentSyncManager =
    DocumentSyncManager(projectManager)

  /** Helper: write content directly to disk via VirtualFile, simulating external editor save. */
  private def writeToDisk(uri: String, content: String): Unit =
    val vf = projectManager.findVirtualFile(uri).get
    ApplicationManager.getApplication.invokeAndWait: () =>
      WriteCommandAction.runWriteCommandAction(projectManager.getProject, (() =>
        vf.setBinaryContent(content.getBytes(StandardCharsets.UTF_8))
      ): Runnable)

  def testDidOpenLoadsContentFromDisk(): Unit =
    val uri = configureScalaFile("Sync.scala",
      """object Sync:
        |  val x = 1
        |""".stripMargin
    )
    val sync = syncManager

    // Write new content to disk (simulating external editor)
    val newContent = "object Sync:\n  val x = 999\n"
    writeToDisk(uri, newContent)

    // didOpen reloads from disk
    sync.didOpen(uri, newContent)

    val vf = projectManager.findVirtualFile(uri).get
    val doc = FileDocumentManager.getInstance().getDocument(vf)
    assertTrue("Document should contain new content", doc.getText.contains("999"))

  def testDidChangeReloadsFromDisk(): Unit =
    val uri = configureScalaFile("Change.scala",
      """object Change:
        |  val original = true
        |""".stripMargin
    )
    val sync = syncManager

    // Write updated content to disk
    val updatedContent = "object Change:\n  val modified = true\n"
    writeToDisk(uri, updatedContent)

    // didChange reloads from disk
    sync.didChange(uri, updatedContent)

    val vf = projectManager.findVirtualFile(uri).get
    val doc = FileDocumentManager.getInstance().getDocument(vf)
    assertTrue("Document should contain updated content", doc.getText.contains("modified"))
    assertFalse("Original content should be gone", doc.getText.contains("val original"))

  def testDidCloseReloadsFromDisk(): Unit =
    val uri = configureScalaFile("Close.scala",
      """object Close:
        |  val diskContent = true
        |""".stripMargin
    )
    val sync = syncManager
    sync.didClose(uri)
    val vf = projectManager.findVirtualFile(uri)
    assertNotNull("File should still exist after close", vf)

  def testDidSaveReloadsFromDisk(): Unit =
    val uri = configureScalaFile("Save.scala",
      """object Save:
        |  val x = 42
        |""".stripMargin
    )
    val sync = syncManager
    sync.didSave(uri)
    val vf = projectManager.findVirtualFile(uri)
    assertTrue("File should still be accessible after save", vf.isDefined)
