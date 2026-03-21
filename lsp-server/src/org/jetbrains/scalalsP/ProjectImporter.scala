package org.jetbrains.scalalsP

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LoggedErrorProcessor
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.util.EnumSet

/**
 * Imports an sbt project by bootstrapping IntelliJ and using the Scala plugin's
 * SbtOpenProjectProvider to generate .idea project files, then exits.
 *
 * Usage: ProjectImporter <project-path>
 */
object ProjectImporter:

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("Usage: ProjectImporter <project-path>")
      System.exit(1)

    val projectPath = args.head
    val path = Path.of(projectPath).toAbsolutePath.normalize
    if !path.toFile.isDirectory then
      System.err.println(s"ERROR: Not a directory: $path")
      System.exit(1)

    System.setOut(new PrintStream(System.err, true))

    // Install lenient error processor before bootstrap
    try
      val field = classOf[LoggedErrorProcessor].getDeclaredField("ourInstance")
      field.setAccessible(true)
      field.set(null, new LoggedErrorProcessor:
        override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
          EnumSet.of(LoggedErrorProcessor.Action.STDERR)
      )
    catch case e: Exception =>
      System.err.println(s"Warning: could not install global error processor: ${e.getMessage}")
      LoggedErrorProcessor.executeWith(new LoggedErrorProcessor:
        override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
          EnumSet.of(LoggedErrorProcessor.Action.STDERR)
      )

    // Bootstrap IntelliJ platform
    try
      System.err.println("[Import] Initializing IntelliJ platform...")
      IntellijBootstrap.initialize()
      System.err.println("[Import] IntelliJ platform initialized")
      BootstrapState.bootstrapComplete.countDown()
    catch
      case e: Exception =>
        System.err.println(s"[Import] Bootstrap failed: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

    // Import the sbt project
    try
      importSbtProject(path)
      System.err.println("Import complete.")
      System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"[Import] sbt import failed: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

  private def importSbtProject(projectPath: Path): Unit =
    System.err.println(s"[Import] Opening project at: $projectPath")

    // Open (or create) the IDEA project
    val project = ProjectManager.getInstance().loadAndOpenProject(projectPath.toString)
    if project == null then
      throw RuntimeException(s"Failed to open project at $projectPath")

    try
      // Use reflection to call SbtOpenProjectProvider.doLinkProject()
      // IMPORTANT: Must load via the Scala plugin's classloader, not the LSP server's.
      // The LSP server's PathClassLoader has its own copy of sbt-api classes, but IntelliJ
      // services (like SbtSettings) are registered by the plugin's PluginClassLoader.
      // Using the wrong classloader causes ClassCastException at runtime.
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
        val sbtSystemId = new ProjectSystemId("SBT")
        ExternalSystemUtil.refreshProject(
          projectPath.toString,
          new ImportSpecBuilder(project, sbtSystemId)
            .use(ProgressExecutionMode.MODAL_SYNC)
        )
      else
        System.err.println("[Import] sbt project linked")

      System.err.println("[Import] Waiting for indexing...")
      DumbService.getInstance(project).waitForSmartMode()
      System.err.println("[Import] Indexing complete")

      // Save all project files to disk
      ApplicationManager.getApplication.invokeAndWait: () =>
        ApplicationManager.getApplication.saveAll()
        project.save()
    finally
      // Close and dispose the project
      ApplicationManager.getApplication.invokeAndWait: () =>
        ProjectManager.getInstance().closeAndDispose(project)

  /** Traverse the exception cause chain looking for AlreadyImportedProjectException */
  private def hasAlreadyImportedException(e: Throwable): Boolean =
    var cause: Throwable = e
    while cause != null do
      if cause.getClass.getSimpleName == "AlreadyImportedProjectException" then
        return true
      cause = cause.getCause
    false
