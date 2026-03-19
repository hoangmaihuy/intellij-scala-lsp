package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiManager}

import java.nio.file.Path

/**
 * Manages the IntelliJ project lifecycle: open, index, close.
 * All IntelliJ platform access goes through this class.
 */
class IntellijProjectManager:

  import scala.compiletime.uninitialized
  @volatile private var project: Project = uninitialized

  def getProject: Project =
    val p = project
    if p == null then throw IllegalStateException("No project is open")
    p

  def openProject(projectPath: String): Unit =
    System.err.println(s"[ProjectManager] Opening project at: $projectPath")
    val path = Path.of(projectPath)

    // Open project on EDT
    ApplicationManager.getApplication.invokeAndWait: () =>
      project = ProjectManager.getInstance().loadAndOpenProject(path.toString)

    if project == null then
      throw RuntimeException(s"Failed to open project at $projectPath")

    System.err.println(s"[ProjectManager] Project opened: ${project.getName}")

  def waitForSmartMode(): Unit =
    val p = getProject
    System.err.println("[ProjectManager] Waiting for indexing to complete...")
    DumbService.getInstance(p).waitForSmartMode()
    System.err.println("[ProjectManager] Indexing complete")

  def closeProject(): Unit =
    val p = project
    if p != null then
      ApplicationManager.getApplication.invokeAndWait: () =>
        ProjectManager.getInstance().closeAndDispose(p)
      project = null
      System.err.println("[ProjectManager] Project closed")

  def findVirtualFile(uri: String): Option[VirtualFile] =
    val path = uriToPath(uri)
    Option(LocalFileSystem.getInstance().findFileByPath(path))

  def findPsiFile(uri: String): Option[PsiFile] =
    findVirtualFile(uri).flatMap: vf =>
      ReadAction.compute[Option[PsiFile], RuntimeException]: () =>
        Option(PsiManager.getInstance(getProject).findFile(vf))

  def getPsiDocumentManager: PsiDocumentManager =
    PsiDocumentManager.getInstance(getProject)

  private def uriToPath(uri: String): String =
    if uri.startsWith("file://") then
      java.net.URI.create(uri).getPath
    else
      uri
