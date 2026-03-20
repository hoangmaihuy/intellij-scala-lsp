package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, ReadAction, WriteAction}
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiManager}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

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
    if project != null then
      System.err.println(s"[ProjectManager] Project already open: ${project.getName}")
      return

    System.err.println(s"[ProjectManager] Opening project at: $projectPath")
    val path = Path.of(projectPath)

    // Open project — loadAndOpenProject handles EDT internally
    project = ProjectManager.getInstance().loadAndOpenProject(path.toString)

    if project == null then
      throw RuntimeException(s"Failed to open project at $projectPath")

    System.err.println(s"[ProjectManager] Project opened: ${project.getName}")

    // Register missing JDKs referenced by project modules.
    // When using TestApplicationManager with an isolated config, the JDK table is empty.
    // Projects imported via BSP reference JDKs like "BSP_Home" that need to be registered.
    registerMissingJdks()

  private def registerMissingJdks(): Unit =
    try
      val jdkTable = ProjectJdkTable.getInstance()

      // Find JavaSdk type via SdkType.findInstance (works across plugin classloaders)
      val javaSdkType = com.intellij.openapi.projectRoots.SdkType.EP_NAME.getExtensionList.asScala
        .find(_.getName == "JavaSDK").orNull
      if javaSdkType == null then
        System.err.println("[ProjectManager] JavaSdk type not found in extensions")
        return

      // Find all unresolved JDK names from project modules
      import com.intellij.openapi.module.ModuleManager
      val modules = ModuleManager.getInstance(project).getModules
      val referencedJdkNames = scala.collection.mutable.Set[String]()
      for m <- modules do
        val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
        if rootManager.getSdk == null then
          val model = rootManager.getModifiableModel
          try Option(model.getSdkName).foreach(n => referencedJdkNames += n)
          finally model.dispose()

      for jdkName <- referencedJdkNames do
        if jdkTable.findJdk(jdkName) == null then
          val jdkHome = findJdkHome()
          jdkHome match
            case Some(home) =>
              System.err.println(s"[ProjectManager] Registering JDK '$jdkName' -> $home")
              ApplicationManager.getApplication.invokeAndWait: () =>
                WriteAction.run[RuntimeException]: () =>
                  val createJdk = javaSdkType.getClass.getMethod("createJdk", classOf[String], classOf[String], classOf[Boolean])
                  val sdk = createJdk.invoke(javaSdkType, jdkName, home, java.lang.Boolean.FALSE)
                    .asInstanceOf[com.intellij.openapi.projectRoots.Sdk]
                  jdkTable.addJdk(sdk)
            case None =>
              System.err.println(s"[ProjectManager] WARNING: Cannot find JDK for '$jdkName'")
    catch
      case e: Exception =>
        System.err.println(s"[ProjectManager] JDK registration failed: ${e.getMessage}")

  private def findJdkHome(): Option[String] =
    // Check common JDK locations
    val candidates = Seq(
      Option(System.getenv("JAVA_HOME")),
      // IntelliJ's bundled JBR
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr/Contents/Home"),
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr"),
      // macOS system JDKs
      Some("/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"),
      Some("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"),
    ).flatten
    candidates.find(p => java.io.File(p + "/bin/java").exists())

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
