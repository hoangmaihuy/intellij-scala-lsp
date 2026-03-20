package org.jetbrains.scalalsP.integration

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.scalalsP.ScalaLspMain
import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.BootstrapState
import org.jetbrains.scalalsP.intellij.{IntellijProjectManager, PsiUtils}

// Base class for LSP integration tests.
// Provides a real IntelliJ project with Scala plugin support via BasePlatformTestCase.
// The Scala plugin is loaded because build.sbt declares intellijPlugins += "org.intellij.scala".
abstract class ScalaLspTestBase extends BasePlatformTestCase:

  import scala.compiletime.uninitialized
  protected var projectManager: IntellijProjectManager = uninitialized

  override def setUp(): Unit =
    super.setUp()
    // In tests, IntelliJ is already bootstrapped by BasePlatformTestCase
    ScalaLspMain.notificationsEnabled = false
    BootstrapState.bootstrapComplete.countDown()
    projectManager = IntellijProjectManager()
    projectManager.setProjectForTesting(getProject)

  override def tearDown(): Unit =
    projectManager = null
    super.tearDown()

  // --- File configuration helpers ---

  /** Configure a Scala file as the active editor file. Returns its file:// URI. */
  protected def configureScalaFile(code: String): String =
    configureScalaFile("Test.scala", code)

  /** Configure a named Scala file as the active editor file. Returns its file:// URI. */
  protected def configureScalaFile(name: String, code: String): String =
    val scalaFileType = FileTypeManager.getInstance().getFileTypeByExtension("scala")
    myFixture.configureByText(scalaFileType, code)
    val vf = myFixture.getFile.getVirtualFile
    val uri = PsiUtils.vfToUri(vf)
    projectManager.registerVirtualFile(uri, vf)
    uri

  /** Add a secondary Scala file to the project (not the active editor). Returns its file:// URI. */
  protected def addScalaFile(path: String, code: String): String =
    val psiFile = myFixture.addFileToProject(path, code)
    val vf = psiFile.getVirtualFile
    val uri = PsiUtils.vfToUri(vf)
    projectManager.registerVirtualFile(uri, vf)
    uri

  // --- Position helpers ---

  /** Create an LSP Position from line and character (0-based). */
  protected def positionAt(line: Int, char: Int): Position =
    Position(line, char)

  /** Get the LSP Position of the editor caret (placed via <caret> marker in configureByText). */
  protected def caretPosition(): Position =
    val editor = myFixture.getEditor
    val offset = editor.getCaretModel.getOffset
    val doc = editor.getDocument
    PsiUtils.offsetToPosition(doc, offset)

  /** Get the IntelliJ Document for the currently configured file. */
  protected def getDocument: com.intellij.openapi.editor.Document =
    FileDocumentManager.getInstance().getDocument(myFixture.getFile.getVirtualFile)

  /** Get the file:// URI for the currently configured file. */
  protected def getUri: String =
    PsiUtils.vfToUri(myFixture.getFile.getVirtualFile)
