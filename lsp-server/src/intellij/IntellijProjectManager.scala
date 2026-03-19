package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiManager}

import java.nio.file.{Files, Path}

/**
 * Manages the IntelliJ project lifecycle: open, index, close.
 * All IntelliJ platform access goes through this class.
 */
class IntellijProjectManager:

  import scala.compiletime.uninitialized
  @volatile private var project: Project = uninitialized

  // Cache for virtual files that are not on the local filesystem (e.g., in-memory test files)
  private val virtualFileCache = scala.collection.concurrent.TrieMap[String, VirtualFile]()

  /** For testing only — allows injecting a project from test fixtures */
  private[scalalsP] def setProjectForTesting(p: Project): Unit =
    project = p

  /** For testing only — registers an in-memory virtual file by URI */
  private[scalalsP] def registerVirtualFile(uri: String, vf: VirtualFile): Unit =
    virtualFileCache.put(uri, vf)

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

    // Auto-import BSP project if .bsp directory exists
    if Files.isDirectory(path.resolve(".bsp")) then
      linkBspProject(path)

  private def linkBspProject(projectPath: Path): Unit =
    System.err.println("[ProjectManager] BSP configuration detected, linking BSP project...")
    try
      // Use reflection to call BspOpenProjectProvider.doLinkProject()
      // because BSP classes are in the Scala plugin, not on compile classpath
      val bspProviderClass = Class.forName("org.jetbrains.bsp.project.importing.BspOpenProjectProvider")
      val provider = bspProviderClass.getDeclaredConstructor().newInstance()

      val vf = LocalFileSystem.getInstance().findFileByPath(projectPath.toString)
      if vf == null then
        System.err.println(s"[ProjectManager] Cannot find virtual file for $projectPath")
        return

      // doLinkProject(VirtualFile, Project) triggers BSP import
      val doLinkMethod = bspProviderClass.getMethod("doLinkProject",
        classOf[VirtualFile], classOf[Project])
      ApplicationManager.getApplication.invokeAndWait: () =>
        doLinkMethod.invoke(provider, vf, project)

      System.err.println("[ProjectManager] BSP project linked and import triggered")
    catch
      case e: ClassNotFoundException =>
        System.err.println("[ProjectManager] BSP support not available (Scala plugin not loaded?)")
      case e: Exception =>
        System.err.println(s"[ProjectManager] BSP auto-import failed: ${e.getMessage}")
        e.printStackTrace(System.err)

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
    virtualFileCache.get(uri).orElse:
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
