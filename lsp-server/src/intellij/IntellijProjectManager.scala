package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiManager}
import org.jetbrains.scalalsP.ProjectRegistry

import java.nio.file.Path

/**
 * Manages the IntelliJ project lifecycle: open, index, close.
 * All IntelliJ platform access goes through this class.
 */
class IntellijProjectManager(registry: Option[ProjectRegistry] = None, daemonMode: Boolean = false):

  import scala.compiletime.uninitialized
  @volatile private var project: Project = uninitialized

  // Map of base path -> Project for multi-workspace support
  private val projects = scala.collection.concurrent.TrieMap[String, Project]()

  // Cache for virtual files that are not on the local filesystem (e.g., in-memory test files)
  private val virtualFileCache = scala.collection.concurrent.TrieMap[String, VirtualFile]()

  /** For testing only — allows injecting a project from test fixtures */
  private[scalalsP] def setProjectForTesting(p: Project): Unit =
    project = p
    if p != null && p.getBasePath != null then projects.put(p.getBasePath, p)

  /** For daemon mode — set a project from ProjectRegistry without opening it */
  private[scalalsP] def setProjectForSession(p: Project): Unit =
    project = p
    if p != null && p.getBasePath != null then projects.put(p.getBasePath, p)

  /** For testing only — registers an in-memory virtual file by URI */
  private[scalalsP] def registerVirtualFile(uri: String, vf: VirtualFile): Unit =
    virtualFileCache.put(uri, vf)

  def getProject: Project =
    val p = project
    if p == null then throw IllegalStateException("No project is open")
    p

  def getProjectForUri(uri: String): Project =
    val path = uriToPath(uri)
    projects.find((basePath, _) => path.startsWith(basePath))
      .map(_(1))
      .getOrElse(getProject)

  def openProject(projectPath: String): Unit =
    if project != null then
      // Check if this path is already tracked
      val normalizedPath = Path.of(projectPath).toString
      if projects.keys.exists(bp => bp == normalizedPath || normalizedPath == project.getBasePath) then
        System.err.println(s"[ProjectManager] Folder already open: $projectPath")
        return
      System.err.println(s"[ProjectManager] Primary project already open: ${project.getName}, opening additional folder: $projectPath")
      // Open additional project for multi-workspace support
      registry match
        case Some(reg) =>
          val additional = reg.openProject(projectPath)
          if additional != null && additional.getBasePath != null then
            projects.put(additional.getBasePath, additional)
        case None =>
          val additional = ProjectManager.getInstance().loadAndOpenProject(normalizedPath)
          if additional != null && additional.getBasePath != null then
            projects.put(additional.getBasePath, additional)
      return

    registry match
      case Some(reg) =>
        System.err.println(s"[ProjectManager] Delegating to ProjectRegistry for: $projectPath")
        project = reg.openProject(projectPath)
        if project != null && project.getBasePath != null then
          projects.put(project.getBasePath, project)
        return
      case None => ()

    System.err.println(s"[ProjectManager] Opening project at: $projectPath")
    val path = Path.of(projectPath)

    // Open project — loadAndOpenProject handles EDT internally
    project = ProjectManager.getInstance().loadAndOpenProject(path.toString)

    if project == null then
      throw RuntimeException(s"Failed to open project at $projectPath")

    if project.getBasePath != null then
      projects.put(project.getBasePath, project)

    System.err.println(s"[ProjectManager] Project opened: ${project.getName}")

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
      if daemonMode then
        // In daemon mode, only clear local state — the project lives in ProjectRegistry
        project = null
        projects.clear()
        virtualFileCache.clear()
        System.err.println("[ProjectManager] Session cleared (daemon mode)")
      else
        ApplicationManager.getApplication.invokeAndWait: () =>
          ProjectManager.getInstance().closeAndDispose(p)
        project = null
        projects.clear()
        System.err.println("[ProjectManager] Project closed")

  def closeProject(folderPath: String): Unit =
    projects.remove(folderPath).foreach: p =>
      if !daemonMode then
        ApplicationManager.getApplication.invokeAndWait: () =>
          ProjectManager.getInstance().closeAndDispose(p)
      if project == p then project = projects.values.headOption.orNull

  def findVirtualFile(uri: String): Option[VirtualFile] =
    virtualFileCache.get(uri).orElse:
      if uri.startsWith("jar:") then
        // JAR-internal URI: jar:file:///path/to.jar!/entry/path
        // Convert to IntelliJ VFS URL: jar:///path/to.jar!/entry/path
        val vfsUrl = uri.replace("jar:file://", "jar://")
        Option(com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(vfsUrl))
      else
        val path = uriToPath(uri)
        // Try local filesystem first, then VirtualFileManager as fallback
        Option(LocalFileSystem.getInstance().findFileByPath(path))
          .orElse(Option(com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(uri)))

  def findPsiFile(uri: String): Option[PsiFile] =
    findVirtualFile(uri).flatMap: vf =>
      ReadAction.compute[Option[PsiFile], RuntimeException]: () =>
        Option(PsiManager.getInstance(getProject).findFile(vf))

  def getPsiDocumentManager: PsiDocumentManager =
    PsiDocumentManager.getInstance(getProject)

  /** Run a read action that waits for smart mode (indexing complete) before executing.
    * This prevents IndexNotReadyException when accessing stub indexes. */
  def smartReadAction[T](compute: () => T): T =
    try
      DumbService.getInstance(getProject).runReadActionInSmartMode[T](() => compute())
    catch
      case _: IllegalStateException =>
        // Fallback for test context where DumbService may not be fully initialized
        ReadAction.compute[T, RuntimeException](() => compute())

  private def uriToPath(uri: String): String =
    if uri.startsWith("file://") then
      java.net.URI.create(uri).getPath
    else
      uri
