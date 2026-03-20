package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.junit.Assert.*

class WorkspaceFoldersIntegrationTest extends ScalaLspTestBase:

  def testGetProjectReturnsProject(): Unit =
    assertNotNull("Should have a project", projectManager.getProject)

  def testGetProjectForUriReturnsProject(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val project = projectManager.getProjectForUri(uri)
    assertNotNull("Should find project for URI", project)

  def testFindPsiFileAcrossProjects(): Unit =
    val uri = configureScalaFile(
      """object Test:
        |  val y = 1
        |""".stripMargin
    )
    val psiFile = projectManager.findPsiFile(uri)
    assertTrue("Should find PSI file", psiFile.isDefined)
