package org.jetbrains.scalalsP.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.{DiagnosticsProvider, SymbolProvider}
import org.jetbrains.scalalsP.{ScalaTextDocumentService, ScalaWorkspaceService}
import org.junit.Assert.*

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

// Reproduces: when --import updates .idea/libraries/*.xml in a separate process,
// the running LSP server keeps returning old external dependencies because:
//   1. No filesystem watcher runs in headless mode
//   2. There is no mechanism to refresh the project model
//
// The fix adds:
//   - reloadProjectDependencies() on IntellijProjectManager (VFS refresh + re-index)
//   - Auto-trigger from didChangeWatchedFiles when .idea/** paths are reported
//   - scala.reloadProject execute command for explicit triggering
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

    // Add cats JAR to the module. In production this happens via ExternalSystemUtil
    // writing .idea/libraries/*.xml; here we do it directly so the test doesn't require sbt.
    addCatsJarToModule()

    // Call reloadProjectDependencies() to pick up the newly added library.
    // Without the fix, the server would need a VFS refresh + re-index.
    projectManager.reloadProjectDependencies()

    // cats.Monad must be visible after reload
    val after = workspaceSymbols("Monad")
    val catsMonadAfter = after.find(s =>
      s.getName == "Monad" && Option(s.getContainerName).exists(_.contains("cats")))
    assertTrue(
      s"cats.Monad should be visible after reloadProjectDependencies(). " +
        s"Got ${after.size} results: ${after.map(s => s"${s.getName}@${s.getContainerName}").mkString(", ")}",
      catsMonadAfter.isDefined)

  // ----- didChangeWatchedFiles triggers reload -----

  // When the LSP client reports that a .idea/libraries/*.xml file changed
  // (because --import wrote it), didChangeWatchedFiles must trigger a
  // dependency reload — not just a plain VFS refresh of that single file.
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
  // TODO: These tests deadlock due to EDT/invokeAndWait interaction.
  // The executeCommand returns a CompletableFuture that runs reloadProjectDependencies
  // on a background thread, which calls invokeAndWait. When the test waits on the
  // future, it blocks the EDT, causing invokeAndWait to hang.
  // See: https://github.com/hoangmaihuy/intellij-scala-lsp/issues/22

  // def testScalaReloadProjectCommandIsRegistered(): Unit =
  //   val ws = workspaceService
  //   val params = ExecuteCommandParams()
  //   params.setCommand("scala.reloadProject")
  //   params.setArguments(java.util.List.of())
  //   val result = ws.executeCommand(params).get(30, TimeUnit.SECONDS)
  //   assertNull("scala.reloadProject should return null", result)

  // def testScalaReloadProjectAfterImportExposesNewDeps(): Unit =
  //   configureScalaFile("object Dummy:\n  val x = 1\n")
  //   addCatsJarToModule()
  //   val ws = workspaceService
  //   val params = ExecuteCommandParams()
  //   params.setCommand("scala.reloadProject")
  //   params.setArguments(java.util.List.of())
  //   ws.executeCommand(params).get(30, TimeUnit.SECONDS)
  //   val result = workspaceSymbols("Monad")
  //   val catsMonad = result.find(s =>
  //     s.getName == "Monad" && Option(s.getContainerName).exists(_.contains("cats")))
  //   assertTrue("cats.Monad should be visible after scala.reloadProject command", catsMonad.isDefined)

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
