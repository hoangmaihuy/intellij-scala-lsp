package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Bridges LSP document synchronization events to IntelliJ's VFS and Document model.
 *
 * Design principle: disk is always the source of truth. Both Claude Code and VS Code
 * write files to disk before sending LSP notifications. We never call document.setText()
 * — instead we always reloadFromDisk. This prevents "memory-disk conflict" errors
 * when multiple editors and IntelliJ's background VFS refresh all touch the same file.
 */
class DocumentSyncManager(projectManager: IntellijProjectManager):

  def didOpen(uri: String, text: String): Unit =
    System.err.println(s"[DocSync] didOpen: $uri")
    reloadFromDisk(uri)

  def didChange(uri: String, text: String): Unit =
    reloadFromDisk(uri)

  def didClose(uri: String): Unit =
    System.err.println(s"[DocSync] didClose: $uri")
    reloadFromDisk(uri)

  def didSave(uri: String): Unit =
    System.err.println(s"[DocSync] didSave: $uri")
    reloadFromDisk(uri)

  private def reloadFromDisk(uri: String): Unit =
    projectManager.findVirtualFile(uri).foreach: vf =>
      ApplicationManager.getApplication.invokeAndWait: () =>
        // Refresh VFS to see latest disk content (safe because we never dirty the document)
        vf.refresh(false, false)
        val fdm = FileDocumentManager.getInstance()
        val document = fdm.getDocument(vf)
        if document != null then
          fdm.reloadFromDisk(document)
          projectManager.getPsiDocumentManager.commitDocument(document)
