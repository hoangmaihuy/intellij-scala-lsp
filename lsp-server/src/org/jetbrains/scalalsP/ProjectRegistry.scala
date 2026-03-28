package org.jetbrains.scalalsP

import com.intellij.openapi.application.{ApplicationManager, WriteAction}
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.nio.file.Path
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class ProjectRegistry:
  private val projects = new ConcurrentHashMap[String, Project]()
  private val refCounts = new ConcurrentHashMap[String, AtomicInteger]()
  private val CACHE_DIR = s"${System.getProperty("user.home")}/.cache/intellij-scala-lsp"
  private val GRACE_PERIOD_SECONDS = 30L

  private val closeScheduler = Executors.newSingleThreadScheduledExecutor: r =>
    val t = new Thread(r, "project-close-scheduler")
    t.setDaemon(true)
    t

  def getProject(path: String): Option[Project] =
    Option(projects.get(canonicalize(path)))

  def openProject(path: String): Project =
    val canonical = canonicalize(path)
    val project = projects.computeIfAbsent(canonical, _ => doOpenProject(canonical))
    writeProjectList()
    project

  /** Open/reuse a project and increment its reference count. */
  def acquireProject(path: String): Project =
    val canonical = canonicalize(path)
    refCounts.computeIfAbsent(canonical, _ => new AtomicInteger(0)).incrementAndGet()
    try
      openProject(path)
    catch case e: Exception =>
      refCounts.get(canonical).decrementAndGet()
      throw e

  /** Decrement reference count; schedule close after grace period if no references remain. */
  def releaseProject(path: String): Unit =
    val canonical = canonicalize(path)
    val count = refCounts.get(canonical)
    if count != null && count.decrementAndGet() <= 0 then
      closeScheduler.schedule((() => closeIfUnreferenced(canonical)): Runnable,
        GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)

  /** List all open project paths */
  def listProjects(): Seq[String] = projects.keys().asScala.toSeq.sorted

  /** For testing — register a pre-opened project */
  private[scalalsP] def registerForTesting(path: String, project: Project): Unit =
    projects.put(canonicalize(path), project)

  /** For testing — unregister a project without closing it (to avoid disposing test-managed projects) */
  private[scalalsP] def unregisterForTesting(path: String): Unit =
    projects.remove(canonicalize(path))
    refCounts.remove(canonicalize(path))

  def closeAll(): Unit =
    closeScheduler.shutdownNow()
    projects.values().asScala.foreach: project =>
      try
        ApplicationManager.getApplication.invokeAndWait: () =>
          ProjectManager.getInstance().closeAndDispose(project)
      catch case e: Exception =>
        System.err.println(s"[ProjectRegistry] Error closing ${project.getName}: ${e.getMessage}")
    projects.clear()
    refCounts.clear()
    writeProjectList()

  private def closeIfUnreferenced(canonical: String): Unit =
    val count = refCounts.get(canonical)
    if count == null || count.get() <= 0 then
      val project = projects.remove(canonical)
      refCounts.remove(canonical)
      if project != null then
        System.err.println(s"[ProjectRegistry] Closing unused project: $canonical")
        try
          ApplicationManager.getApplication.invokeAndWait: () =>
            ProjectManager.getInstance().closeAndDispose(project)
        catch case e: Exception =>
          System.err.println(s"[ProjectRegistry] Error closing $canonical: ${e.getMessage}")
      writeProjectList()

  /** Persist open project paths to disk so --list-projects can read them */
  private def writeProjectList(): Unit =
    try
      val dir = java.nio.file.Path.of(CACHE_DIR)
      java.nio.file.Files.createDirectories(dir)
      val content = projects.keys().asScala.toSeq.sorted.mkString("\n")
      java.nio.file.Files.writeString(dir.resolve("daemon.projects"), content)
    catch case _: Exception => ()

  private def doOpenProject(projectPath: String): Project =
    System.err.println(s"[ProjectRegistry] Opening project at: $projectPath")
    VfsRootAccess.allowRootAccess(ApplicationManager.getApplication, projectPath)
    val project = ProjectManager.getInstance().loadAndOpenProject(projectPath)
    if project == null then throw RuntimeException(s"Failed to open project at $projectPath")
    System.err.println(s"[ProjectRegistry] Project opened: ${project.getName}")
    ensureJdkRegistered()
    refreshExternalProject(project, projectPath)
    registerMissingJdks(project)
    System.err.println(s"[ProjectRegistry] Waiting for indexing: ${project.getName}")
    DumbService.getInstance(project).waitForSmartMode()
    System.err.println(s"[ProjectRegistry] Indexing complete: ${project.getName}")
    project

  /** Register a JDK so sbt resolution can find a Java VM executable. */
  private def ensureJdkRegistered(): Unit =
    try
      val jdkTable = ProjectJdkTable.getInstance()
      val javaSdkType = com.intellij.openapi.projectRoots.SdkType.EP_NAME.getExtensionList.asScala
        .find(_.getName == "JavaSDK").orNull
      if javaSdkType == null then return
      if jdkTable.getAllJdks.exists(_.getSdkType.getName == "JavaSDK") then return

      // Prefer IntelliJ's bundled JBR (in allowed VFS roots), then JAVA_HOME
      val homePath = com.intellij.openapi.application.PathManager.getHomePath
      val candidates = Seq(
        Some(homePath + "/jbr/Contents/Home"),
        Some(homePath + "/jbr"),
        Option(System.getenv("JAVA_HOME")),
      ).flatten.filter(p => java.io.File(p + "/bin/java").exists())

      candidates.headOption.foreach: home =>
        System.err.println(s"[ProjectRegistry] Registering JDK: $home")
        VfsRootAccess.allowRootAccess(ApplicationManager.getApplication, home)
        ApplicationManager.getApplication.invokeAndWait: () =>
          WriteAction.run[RuntimeException]: () =>
            val createJdk = javaSdkType.getClass.getMethod("createJdk", classOf[String], classOf[String], classOf[Boolean])
            val sdk = createJdk.invoke(javaSdkType, "java", home, java.lang.Boolean.FALSE)
              .asInstanceOf[com.intellij.openapi.projectRoots.Sdk]
            jdkTable.addJdk(sdk)
    catch case e: Exception =>
      System.err.println(s"[ProjectRegistry] JDK registration failed: ${e.getMessage}")

  /** Refresh external system (sbt/mill) to ensure modules and source roots are resolved. */
  private def refreshExternalProject(project: Project, projectPath: String): Unit =
    try
      import com.intellij.openapi.externalSystem.model.ProjectSystemId
      import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
      import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
      import com.intellij.openapi.externalSystem.util.ExternalSystemUtil

      // Check if build.sbt exists (simple heuristic for sbt projects)
      if !java.io.File(projectPath, "build.sbt").exists() then return

      val sbtSystemId = new ProjectSystemId("SBT")
      System.err.println(s"[ProjectRegistry] Refreshing sbt project: ${project.getName}")
      ExternalSystemUtil.refreshProject(
        projectPath,
        new ImportSpecBuilder(project, sbtSystemId)
          .use(ProgressExecutionMode.MODAL_SYNC)
      )
      System.err.println(s"[ProjectRegistry] sbt refresh complete: ${project.getName}")
    catch case e: Exception =>
      System.err.println(s"[ProjectRegistry] External project refresh failed: ${e.getMessage}")

  private def registerMissingJdks(project: Project): Unit =
    try
      val jdkTable = ProjectJdkTable.getInstance()
      val javaSdkType = com.intellij.openapi.projectRoots.SdkType.EP_NAME.getExtensionList.asScala
        .find(_.getName == "JavaSDK").orNull
      if javaSdkType == null then return
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
          findJdkHome().foreach: home =>
            System.err.println(s"[ProjectRegistry] Registering JDK '$jdkName' -> $home")
            VfsRootAccess.allowRootAccess(ApplicationManager.getApplication, home)
            ApplicationManager.getApplication.invokeAndWait: () =>
              WriteAction.run[RuntimeException]: () =>
                val createJdk = javaSdkType.getClass.getMethod("createJdk", classOf[String], classOf[String], classOf[Boolean])
                val sdk = createJdk.invoke(javaSdkType, jdkName, home, java.lang.Boolean.FALSE)
                  .asInstanceOf[com.intellij.openapi.projectRoots.Sdk]
                jdkTable.addJdk(sdk)
    catch case e: Exception =>
      System.err.println(s"[ProjectRegistry] JDK registration failed: ${e.getMessage}")

  private def findJdkHome(): Option[String] =
    val candidates = Seq(
      Option(System.getenv("JAVA_HOME")),
      Some("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
      Some("/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"),
      Some("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"),
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr/Contents/Home"),
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr"),
    ).flatten.filter(p => java.io.File(p + "/bin/java").exists())
    // Prefer JDKs that have src.zip (needed for source navigation)
    val withSources = candidates.find(p => java.io.File(p + "/lib/src.zip").exists())
    withSources.orElse(candidates.headOption)

  private def canonicalize(path: String): String =
    Path.of(path).toAbsolutePath.normalize.toString
