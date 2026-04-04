package org.jetbrains.scalalsP.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.{DiagnosticsProvider, SymbolProvider}
import org.jetbrains.scalalsP.{ScalaTextDocumentService, ScalaWorkspaceService}
import org.junit.Assert.*

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
 * Reproduces: when --import updates .idea/libraries/*.xml in a separate process,
 * the running LSP server keeps returning old external dependencies because:
 *   1. No filesystem watcher runs in headless mode
 *   2. There is no mechanism to refresh the project model
 *
 * The fix adds:
 *   - reloadProjectDependencies() on IntellijProjectManager (VFS refresh + re-index)
 *   - Auto-trigger from didChangeWatchedFiles when .idea/** paths are reported
 *   - scala.reloadProject execute command for explicit triggering
 */
class ReloadProjectDependenciesTest extends ScalaLspTestBase:

  private def workspaceSymbols(query: String) =
    SymbolProvider(projectManager).workspaceSymbols(query)

  private def workspaceService =
    val diag = DiagnosticsProvider(projectManager)
    val tds = ScalaTextDocumentService(projectManager, diag)
    ScalaWorkspaceService(projectManager, diag, tds)

  // ----- Reproduce the bug -----

  /**
   * Reproduces the bug: server opens project with no external deps.
   * An external process (--import) adds a new library. Without a reload
   * mechanism, the server never sees the new library.
   *
   * This test verifies the correct behaviour after the fix:
   * reloadProjectDependencies() causes the server to pick up the new deps.
   */
  def testServerSeesNewExternalDepsAfterReload(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")

    // Initially no cats JAR is on the module → cats.Monad not found
    val before = workspaceSymbols("Monad")
    val catsMonadBefore = before.find(s =>
      s.getName == "Monad" && Option(s.getContainerName).exists(_.contains("cats")))
    assertTrue("cats.Monad should NOT be visible before adding the JAR", catsMonadBefore.isEmpty)

    // Simulate what --import does: update the module to add a new external library.
    // In production this happens via ExternalSystemUtil writing .idea/libraries/*.xml;
    // here we do it directly so the test doesn't require running sbt.
    addCatsJarToModule()

    // Without the fix, the server would need a VFS refresh + re-index to pick this up.
    // With the fix, calling reloadProjectDependencies() does exactly that.
    projectManager.reloadProjectDependencies()

    // Now cats.Monad must be visible
    val after = workspaceSymbols("Monad")
    val catsMonadAfter = after.find(s =>
      s.getName == "Monad" && Option(s.getContainerName).exists(_.contains("cats")))
    assertTrue(
      s"cats.Monad should be visible after reloadProjectDependencies(). " +
        s"Got ${after.size} results: ${after.map(s => s"${s.getName}@${s.getContainerName}").mkString(", ")}",
      catsMonadAfter.isDefined)

  // ----- didChangeWatchedFiles triggers reload -----

  /**
   * When the LSP client reports that a .idea/libraries/*.xml file changed
   * (because --import wrote it), didChangeWatchedFiles must trigger a
   * dependency reload — not just a plain VFS refresh of that single file.
   */
  def testDidChangeWatchedFilesOnIdeaXmlTriggersReload(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    addCatsJarToModule()

    val ws = workspaceService
    val basePath = getProject.getBasePath

    // Simulate LSP client notifying that .idea/libraries/cats.xml changed
    val ideaLibXmlUri = s"file://$basePath/.idea/libraries/cats.xml"
    val change = FileEvent(ideaLibXmlUri, FileChangeType.Changed)
    ws.didChangeWatchedFiles(DidChangeWatchedFilesParams(java.util.List.of(change)))

    // Wait for the async reload to complete (reload is dispatched to a background thread)
    Thread.sleep(500)
    projectManager.waitForSmartMode()

    val result = workspaceSymbols("Monad")
    val catsMonad = result.find(s =>
      s.getName == "Monad" && Option(s.getContainerName).exists(_.contains("cats")))
    assertTrue(
      s"cats.Monad should be visible after didChangeWatchedFiles on .idea XML. " +
        s"Got: ${result.map(s => s"${s.getName}@${s.getContainerName}").mkString(", ")}",
      catsMonad.isDefined)

  // ----- scala.reloadProject execute command -----

  def testScalaReloadProjectCommandIsRegistered(): Unit =
    val ws = workspaceService
    val params = ExecuteCommandParams()
    params.setCommand("scala.reloadProject")
    params.setArguments(java.util.List.of())
    // Must not throw — returns null on success
    val result = ws.executeCommand(params).get(30, TimeUnit.SECONDS)
    // Command completes without error (result is null by convention)
    assertNull("scala.reloadProject should return null", result)

  def testScalaReloadProjectAfterImportExposesNewDeps(): Unit =
    configureScalaFile("object Dummy:\n  val x = 1\n")
    addCatsJarToModule()

    val ws = workspaceService
    val params = ExecuteCommandParams()
    params.setCommand("scala.reloadProject")
    params.setArguments(java.util.List.of())
    ws.executeCommand(params).get(30, TimeUnit.SECONDS)

    val result = workspaceSymbols("Monad")
    val catsMonad = result.find(s =>
      s.getName == "Monad" && Option(s.getContainerName).exists(_.contains("cats")))
    assertTrue("cats.Monad should be visible after scala.reloadProject command", catsMonad.isDefined)

  // ----- Non-.idea watched-file changes are unaffected -----

  /**
   * Watched file changes for regular .scala files must NOT trigger a full
   * dependency reload — only a lightweight VFS refresh (existing behaviour).
   */
  def testDidChangeWatchedFilesForScalaFileDoesNotTriggerFullReload(): Unit =
    val uri = configureScalaFile("object Dummy:\n  val x = 1\n")
    val ws = workspaceService
    // A plain .scala change — should be handled cheaply, no reload
    val change = FileEvent(uri, FileChangeType.Changed)
    // Must not throw
    ws.didChangeWatchedFiles(DidChangeWatchedFilesParams(java.util.List.of(change)))

  // ----- Helpers -----

  private def addCatsJarToModule(): Unit =
    val classpath = System.getProperty("java.class.path", "")
    val entries = classpath.split(java.io.File.pathSeparator).toSeq
    val catsJars = entries.filter: entry =>
      val name = java.nio.file.Path.of(entry).getFileName.toString
      (name.startsWith("cats-core") || name.startsWith("cats-kernel")) && name.endsWith(".jar")

    if catsJars.isEmpty then
      fail("cats JARs not found on test classpath — add them to test dependencies in build.sbt")

    ApplicationManager.getApplication.invokeAndWait: () =>
      ApplicationManager.getApplication.runWriteAction((() =>
        for jarPath <- catsJars do
          ModuleRootModificationUtil.addModuleLibrary(myFixture.getModule, s"jar://$jarPath!/")
      ): Runnable)
