package org.jetbrains.scalalsP

import com.intellij.openapi.application.{ApplicationManager, WriteAction}
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.projectRoots.ProjectJdkTable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class ProjectRegistry:
  private val projects = new ConcurrentHashMap[String, Project]()

  def getProject(path: String): Option[Project] =
    Option(projects.get(canonicalize(path)))

  def openProject(path: String): Project =
    val canonical = canonicalize(path)
    projects.computeIfAbsent(canonical, _ => doOpenProject(canonical))

  /** For testing — register a pre-opened project */
  private[scalalsP] def registerForTesting(path: String, project: Project): Unit =
    projects.put(canonicalize(path), project)

  /** For testing — unregister a project without closing it (to avoid disposing test-managed projects) */
  private[scalalsP] def unregisterForTesting(path: String): Unit =
    projects.remove(canonicalize(path))

  def closeAll(): Unit =
    projects.values().asScala.foreach: project =>
      try
        ApplicationManager.getApplication.invokeAndWait: () =>
          ProjectManager.getInstance().closeAndDispose(project)
      catch case e: Exception =>
        System.err.println(s"[ProjectRegistry] Error closing ${project.getName}: ${e.getMessage}")
    projects.clear()

  private def doOpenProject(projectPath: String): Project =
    System.err.println(s"[ProjectRegistry] Opening project at: $projectPath")
    val project = ProjectManager.getInstance().loadAndOpenProject(projectPath)
    if project == null then throw RuntimeException(s"Failed to open project at $projectPath")
    System.err.println(s"[ProjectRegistry] Project opened: ${project.getName}")
    registerMissingJdks(project)
    System.err.println(s"[ProjectRegistry] Waiting for indexing: ${project.getName}")
    DumbService.getInstance(project).waitForSmartMode()
    System.err.println(s"[ProjectRegistry] Indexing complete: ${project.getName}")
    project

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
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr/Contents/Home"),
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr"),
      Some("/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"),
      Some("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"),
    ).flatten
    candidates.find(p => java.io.File(p + "/bin/java").exists())

  private def canonicalize(path: String): String =
    Path.of(path).toAbsolutePath.normalize.toString
