package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, WriteAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Bridges LSP document synchronization events to IntelliJ's VFS and Document model.
 * All document modifications run on the EDT under write lock as required by IntelliJ.
 */
class DocumentSyncManager(projectManager: IntellijProjectManager):

  def didOpen(uri: String, text: String): Unit =
    System.err.println(s"[DocSync] didOpen: $uri")
    try updateDocument(uri, text)
    catch case e: Exception =>
      System.err.println(s"[DocSync] Error opening $uri: ${e.getMessage}")

  def didChange(uri: String, text: String): Unit =
    updateDocument(uri, text)

  def didClose(uri: String): Unit =
    System.err.println(s"[DocSync] didClose: $uri")
    // Reload from disk to discard in-memory changes
    projectManager.findVirtualFile(uri).foreach: vf =>
      ApplicationManager.getApplication.invokeAndWait: () =>
        vf.refresh(false, false)

  def didSave(uri: String): Unit =
    System.err.println(s"[DocSync] didSave: $uri")
    // Refresh VFS to pick up disk changes
    projectManager.findVirtualFile(uri).foreach: vf =>
      ApplicationManager.getApplication.invokeAndWait: () =>
        vf.refresh(false, false)

  private def updateDocument(uri: String, text: String): Unit =
    projectManager.findVirtualFile(uri) match
      case Some(vf) =>
        ApplicationManager.getApplication.invokeAndWait: () =>
          WriteCommandAction.runWriteCommandAction(projectManager.getProject, (() =>
            val document = FileDocumentManager.getInstance().getDocument(vf)
            if document != null then
              document.setText(text)
              projectManager.getPsiDocumentManager.commitDocument(document)
          ): Runnable)
      case None =>
        System.err.println(s"[DocSync] WARNING: File not found for URI: $uri")
