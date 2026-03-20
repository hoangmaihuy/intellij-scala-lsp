package org.jetbrains.scalalsP.e2e

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.scalalsP.{BootstrapState, ScalaLspMain, ScalaLspServer}
import org.jetbrains.scalalsP.intellij.{IntellijProjectManager, PsiUtils}

import java.nio.file.{Files, Path}
import java.util.EnumSet
import scala.compiletime.uninitialized

abstract class E2eTestBase extends BasePlatformTestCase:

  protected var client: TestLspClient = uninitialized
  protected var projectManager: IntellijProjectManager = uninitialized

  // Map of relative path -> URI for loaded fixture files
  protected val fixtureUris = scala.collection.mutable.Map[String, String]()

  private val fixtureBasePath: Path =
    Path.of("test/resources/fixtures/shared-src/src/main/scala")

  override def setUp(): Unit =
    super.setUp()
    // Install lenient error processor to prevent TestLoggerFactory from converting
    // LOG.error() into thrown exceptions. LSP server operations (document sync, indexing)
    // may trigger non-critical errors that should be logged, not crash the test.
    LoggedErrorProcessor.executeWith(new LoggedErrorProcessor:
      override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
        EnumSet.of(LoggedErrorProcessor.Action.STDERR)
    )
    ScalaLspMain.notificationsEnabled = false
    BootstrapState.bootstrapComplete.countDown()
    projectManager = IntellijProjectManager()
    projectManager.setProjectForTesting(getProject)

    // Load all fixture files into the test project
    loadFixtureFiles()

    // Create in-process LSP client
    val server = new ScalaLspServer(getProject.getBasePath, projectManager)
    client = TestLspClient.inProcess(server)
    client.initialize(s"file://${getProject.getBasePath}")

  override def tearDown(): Unit =
    try client.shutdown()
    catch case _: Exception => ()
    projectManager = null
    fixtureUris.clear()
    super.tearDown()

  private def loadFixtureFiles(): Unit =
    val base = fixtureBasePath
    if !Files.exists(base) then
      throw AssertionError(
        s"Fixture sources not found at $base. " +
        "Ensure you are running tests from the project root."
      )

    Files.walk(base).filter(_.toString.endsWith(".scala")).forEach: path =>
      val relativePath = base.relativize(path).toString
      val content = Files.readString(path)
      val psiFile = myFixture.addFileToProject(relativePath, content)
      val vf = psiFile.getVirtualFile
      val uri = PsiUtils.vfToUri(vf)
      projectManager.registerVirtualFile(uri, vf)
      fixtureUris.put(relativePath, uri)

  protected def fixtureUri(relativePath: String): String =
    fixtureUris.getOrElse(relativePath,
      throw AssertionError(s"Fixture file not loaded: $relativePath"))

  protected def fixtureContent(relativePath: String): String =
    Files.readString(fixtureBasePath.resolve(relativePath))

  /** "Open" a fixture file for LSP operations. Since the file is already loaded
    * into the test project via myFixture.addFileToProject(), we just return the URI.
    * We do NOT call client.openFile() because that triggers DocumentSyncManager.updateDocument()
    * which conflicts with the test framework's document change tracking. */
  protected def openFixture(relativePath: String): String =
    fixtureUri(relativePath)
