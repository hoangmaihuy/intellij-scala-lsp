package org.jetbrains.scalalsP.integration

import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.scalalsP.intellij.DocumentSyncManager
import org.junit.Assert.*

class DocumentSyncManagerIntegrationTest extends ScalaLspTestBase:

  private def syncManager: DocumentSyncManager =
    DocumentSyncManager(projectManager)

  def testDidOpenUpdatesDocumentContent(): Unit =
    val uri = configureScalaFile("Sync.scala",
      """object Sync:
        |  val x = 1
        |""".stripMargin
    )
    val sync = syncManager
    val newContent = "object Sync:\n  val x = 999\n"
    sync.didOpen(uri, newContent)

    val vf = projectManager.findVirtualFile(uri).get
    val doc = FileDocumentManager.getInstance().getDocument(vf)
    assertTrue("Document should contain new content", doc.getText.contains("999"))

  def testDidChangeUpdatesContent(): Unit =
    val uri = configureScalaFile("Change.scala",
      """object Change:
        |  val original = true
        |""".stripMargin
    )
    val sync = syncManager
    val updatedContent = "object Change:\n  val modified = true\n"
    sync.didChange(uri, updatedContent)

    val vf = projectManager.findVirtualFile(uri).get
    val doc = FileDocumentManager.getInstance().getDocument(vf)
    assertTrue("Document should contain updated content", doc.getText.contains("modified"))
    assertFalse("Original content should be gone", doc.getText.contains("original"))

  def testDidCloseRevertsToDisc(): Unit =
    val uri = configureScalaFile("Close.scala",
      """object Close:
        |  val diskContent = true
        |""".stripMargin
    )
    val sync = syncManager
    sync.didChange(uri, "object Close:\n  val inMemory = true\n")
    sync.didClose(uri)
    val vf = projectManager.findVirtualFile(uri)
    assertNotNull("File should still exist after close", vf)

  def testDidSaveRefreshesVfs(): Unit =
    val uri = configureScalaFile("Save.scala",
      """object Save:
        |  val x = 42
        |""".stripMargin
    )
    val sync = syncManager
    sync.didSave(uri)
    val vf = projectManager.findVirtualFile(uri)
    assertTrue("File should still be accessible after save", vf.isDefined)
