package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiManager}
import org.jetbrains.scalalsP.ProjectRegistry

import java.nio.file.Path
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, Executors, TimeUnit}

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

  // Delayed close: projects are kept warm for a grace period after close, in case client reconnects
  private val closeScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r =>
    val t = Thread(r, "project-close-scheduler")
    t.setDaemon(true)
    t
  )
  private val pendingCloses = scala.collection.concurrent.TrieMap[String, ScheduledFuture[?]]()
  private val CloseDelayMinutes = 5L

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

  def getAllProjects: Seq[Project] =
    projects.values.toSeq.distinct

  def getProjectForUri(uri: String): Project =
    val path = uriToPath(uri)
    projects.find((basePath, _) => path.startsWith(basePath))
      .map(_(1))
      .getOrElse(getProject)

  def openProject(projectPath: String): Unit =
    // Cancel any pending delayed close for this path
    val normalizedForCancel = Path.of(projectPath).toString
    pendingCloses.remove(normalizedForCancel).foreach: future =>
      future.cancel(false)
      System.err.println(s"[ProjectManager] Cancelled pending close for: $projectPath")

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
        // Delay actual disposal — client may reconnect shortly
        val basePath = Option(p.getBasePath).getOrElse("")
        System.err.println(s"[ProjectManager] Scheduling project close in ${CloseDelayMinutes}m: $basePath")
        project = null
        projects.clear()
        val future = closeScheduler.schedule((() =>
          System.err.println(s"[ProjectManager] Closing project after delay: $basePath")
          ApplicationManager.getApplication.invokeAndWait: () =>
            ProjectManager.getInstance().closeAndDispose(p)
          pendingCloses.remove(basePath)
          System.err.println("[ProjectManager] Project closed")
        ): Runnable, CloseDelayMinutes, TimeUnit.MINUTES)
        if basePath.nonEmpty then pendingCloses.put(basePath, future)

  def closeProject(folderPath: String): Unit =
    projects.remove(folderPath).foreach: p =>
      if project == p then project = projects.values.headOption.orNull
      if !daemonMode then
        System.err.println(s"[ProjectManager] Scheduling folder close in ${CloseDelayMinutes}m: $folderPath")
        val future = closeScheduler.schedule((() =>
          System.err.println(s"[ProjectManager] Closing folder after delay: $folderPath")
          ApplicationManager.getApplication.invokeAndWait: () =>
            ProjectManager.getInstance().closeAndDispose(p)
          pendingCloses.remove(folderPath)
        ): Runnable, CloseDelayMinutes, TimeUnit.MINUTES)
        pendingCloses.put(folderPath, future)

  def findVirtualFile(uri: String): Option[VirtualFile] =
    virtualFileCache.get(uri).orElse:
      if uri.startsWith("jar:") then
        // JAR-internal URI: jar:file:///path/to.jar!/entry/path
        // Convert to IntelliJ VFS URL: jar:///path/to.jar!/entry/path
        val vfsUrl = uri.replace("jar:file://", "jar://")
        Option(com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(vfsUrl))
      else
        val path = uriToPath(uri)
        // Try local filesystem first, refresh if not found (handles newly cached external sources)
        Option(LocalFileSystem.getInstance().findFileByPath(path))
          .orElse(Option(LocalFileSystem.getInstance().refreshAndFindFileByPath(path)))
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
