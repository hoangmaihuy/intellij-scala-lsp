package org.jetbrains.scalalsP

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LoggedErrorProcessor
import java.io.PrintStream
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
      System.err.println("[Import] Linking sbt project via Scala plugin...")
      val sbtProviderClass = Class.forName("org.jetbrains.sbt.project.SbtOpenProjectProvider")
      val provider = sbtProviderClass.getDeclaredConstructor().newInstance()

      val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(projectPath.toString)
      if vf == null then
        throw RuntimeException(s"Cannot find virtual file for $projectPath")

      val doLinkMethod = sbtProviderClass.getMethod("doLinkProject",
        classOf[com.intellij.openapi.vfs.VirtualFile], classOf[Project])
      ApplicationManager.getApplication.invokeAndWait: () =>
        doLinkMethod.invoke(provider, vf, project)

      System.err.println("[Import] sbt project linked, waiting for indexing...")
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
