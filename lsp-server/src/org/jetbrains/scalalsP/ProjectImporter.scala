package org.jetbrains.scalalsP

import com.intellij.openapi.application.{ApplicationManager, ApplicationStarter}
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Imports an sbt project by using the Scala plugin's SbtOpenProjectProvider
 * to generate .idea project files, then exits.
 *
 * Registered as ApplicationStarter with id="scala-lsp-import" in plugin.xml.
 * Invoked via: com.intellij.idea.Main scala-lsp-import <project-path>
 */
class ProjectImporter extends ApplicationStarter:

  override def getRequiredModality: Int = ApplicationStarter.NOT_IN_EDT

  override def main(args: java.util.List[String]): Unit =
    // args = ["scala-lsp-import", projectPath]
    val projectPath = if args.size() > 1 then args.get(1) else
      System.err.println("Usage: com.intellij.idea.Main scala-lsp-import <project-path>")
      System.exit(1)
      return

    // Platform is fully initialized
    BootstrapState.bootstrapComplete.countDown()

    val path = Path.of(projectPath).toAbsolutePath.normalize
    if !path.toFile.isDirectory then
      System.err.println(s"ERROR: Not a directory: $path")
      System.exit(1)

    try
      ProjectImporter.importSbtProject(path)
      System.err.println("Import complete.")
      System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"[Import] sbt import failed: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

object ProjectImporter:

  def importSbtProject(projectPath: Path): Unit =
    System.err.println(s"[Import] Opening project at: $projectPath")

    val project = ProjectManager.getInstance().loadAndOpenProject(projectPath.toString)
    if project == null then
      throw RuntimeException(s"Failed to open project at $projectPath")

    try
      doImport(project, projectPath)
    finally
      ApplicationManager.getApplication.invokeAndWait: () =>
        ProjectManager.getInstance().closeAndDispose(project)

  /** Import using an already-open project (e.g. from daemon's ProjectRegistry). */
  def importSbtProjectWithExisting(project: Project, projectPath: Path): Unit =
    System.err.println(s"[Import] Importing with existing project at: $projectPath")
    doImport(project, projectPath)

  private def doImport(project: Project, projectPath: Path): Unit =
    ensureJdkRegistered()

    // Use reflection to call SbtOpenProjectProvider.doLinkProject()
    // Must load via Scala plugin's classloader for proper service registration.
    System.err.println("[Import] Linking sbt project via Scala plugin...")
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("org.intellij.scala")
    val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
    if plugin == null then
      throw RuntimeException("Scala plugin not found. Ensure the Scala plugin is installed.")
    val pluginClassLoader = plugin.getPluginClassLoader
    val sbtProviderClass = Class.forName("org.jetbrains.sbt.project.SbtOpenProjectProvider", true, pluginClassLoader)
    val provider = sbtProviderClass.getDeclaredConstructor().newInstance()

    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(projectPath.toString)
    if vf == null then
      throw RuntimeException(s"Cannot find virtual file for $projectPath")

    val doLinkMethod = sbtProviderClass.getMethod("doLinkProject",
      classOf[com.intellij.openapi.vfs.VirtualFile], classOf[Project])
    var alreadyLinked = false
    try
      ApplicationManager.getApplication.invokeAndWait: () =>
        doLinkMethod.invoke(provider, vf, project)
    catch
      case e: Exception if hasAlreadyImportedException(e) =>
        alreadyLinked = true

    if alreadyLinked then
      System.err.println("[Import] Project already linked, refreshing...")
    else
      System.err.println("[Import] sbt project linked")

    System.err.println("[Import] Waiting for initial indexing...")
    DumbService.getInstance(project).waitForSmartMode()

    System.err.println("[Import] Resolving sbt project model (synchronous)...")
    val sbtSystemId = new ProjectSystemId("SBT")
    ExternalSystemUtil.refreshProject(
      projectPath.toString,
      new ImportSpecBuilder(project, sbtSystemId)
        .use(ProgressExecutionMode.MODAL_SYNC)
    )

    System.err.println("[Import] Waiting for indexing...")
    DumbService.getInstance(project).waitForSmartMode()
    System.err.println("[Import] Indexing complete")

    ApplicationManager.getApplication.invokeAndWait: () =>
      ApplicationManager.getApplication.saveAll()
      project.save()

  private def ensureJdkRegistered(): Unit =
    import com.intellij.openapi.application.WriteAction
    import com.intellij.openapi.projectRoots.{ProjectJdkTable, SdkType}
    import scala.jdk.CollectionConverters.*

    try
      val jdkTable = ProjectJdkTable.getInstance()
      val javaSdkType = SdkType.EP_NAME.getExtensionList.asScala
        .find(_.getName == "JavaSDK").orNull
      if javaSdkType == null then
        System.err.println("[Import] Warning: JavaSDK type not found")
        return

      if jdkTable.getAllJdks.exists(_.getSdkType.getName == "JavaSDK") then
        System.err.println("[Import] JDK already registered")
        return

      val homePath = com.intellij.openapi.application.PathManager.getHomePath
      val jbrCandidates = Seq(
        homePath + "/jbr/Contents/Home",
        homePath + "/jbr",
      )
      val jbrHome = jbrCandidates.find(p => java.io.File(p + "/bin/java").exists())

      if jbrHome.isEmpty then
        val systemCandidates = Seq(
          Option(System.getenv("JAVA_HOME")),
          Some("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
          Some("/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"),
        ).flatten.filter(p => java.io.File(p + "/bin/java").exists())
        if systemCandidates.isEmpty then
          System.err.println("[Import] Warning: No JDK found. Set JAVA_HOME to fix sbt resolution.")
          return
        val home = systemCandidates.head
        try
          val vfsRootAccessClass = Class.forName("com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess")
          val allowMethod = vfsRootAccessClass.getMethod("allowRootAccess", classOf[com.intellij.openapi.Disposable], classOf[Array[String]])
          allowMethod.invoke(null, ApplicationManager.getApplication, Array(home))
        catch case _: Exception => ()
        registerJdk(jdkTable, javaSdkType, home)
        return

      val home = jbrHome.get
      System.err.println(s"[Import] Registering JDK (JBR): $home")
      registerJdk(jdkTable, javaSdkType, home)
    catch case e: Exception =>
      System.err.println(s"[Import] JDK registration failed: ${e.getMessage}")
      e.printStackTrace(System.err)

  private def registerJdk(jdkTable: com.intellij.openapi.projectRoots.ProjectJdkTable,
                          javaSdkType: com.intellij.openapi.projectRoots.SdkType,
                          home: String): Unit =
    import com.intellij.openapi.application.WriteAction
    System.err.println(s"[Import] Registering JDK: $home")
    ApplicationManager.getApplication.invokeAndWait: () =>
      WriteAction.run[RuntimeException]: () =>
        try
          val createJdk = javaSdkType.getClass.getMethod("createJdk", classOf[String], classOf[String], classOf[Boolean])
          val sdk = createJdk.invoke(javaSdkType, "java", home, java.lang.Boolean.FALSE)
            .asInstanceOf[com.intellij.openapi.projectRoots.Sdk]
          jdkTable.addJdk(sdk)
          System.err.println(s"[Import] JDK registered successfully")
        catch case e: Exception =>
          System.err.println(s"[Import] JDK createJdk failed: ${e.getMessage}")
          e.printStackTrace(System.err)

  private def hasAlreadyImportedException(e: Throwable): Boolean =
    var cause: Throwable = e
    while cause != null do
      if cause.getClass.getSimpleName == "AlreadyImportedProjectException" then
        return true
      cause = cause.getCause
    false
