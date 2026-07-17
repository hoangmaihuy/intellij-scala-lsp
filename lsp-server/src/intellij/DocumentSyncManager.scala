package org.jetbrains.scalalsP.intellij

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
      // Async VFS refresh. A synchronous refresh(false, ...) fires its events inside a
      // non-cancellable EDT write action; when that write action stalls while acquiring the write
      // lock, every LSP request parks in NonBlockingReadAction.blockUntilWriteActionIsDone behind it
      // and the daemon wedges (serving nothing). refresh(asynchronous = true, ...) runs the refresh
      // off the EDT and invokes the post-runnable on the EDT once it completes, so the document
      // reload still sees fresh VFS state without blocking. Mirrors the async-refresh fix for
      // reloadProjectDependencies. Disk is the source of truth, so a request racing ahead of the
      // reload sees at worst slightly stale data that the next notification corrects.
      val reloadDocument: Runnable = () =>
        // The post-runnable runs on the EDT after the async refresh completes — which may be after the
        // project has been closed (e.g. during shutdown/teardown). Guard against touching a disposed
        // project (getPsiDocumentManager would throw "No project is open").
        if projectManager.isProjectOpen && vf.isValid then
          val fdm = FileDocumentManager.getInstance()
          val document = fdm.getDocument(vf)
          if document != null then
            fdm.reloadFromDisk(document)
            projectManager.getPsiDocumentManager.commitDocument(document)
      vf.refresh(true, false, reloadDocument)
