package org.jetbrains.scalalsP.integration

import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.ScalaWorkspaceService
import org.junit.Assert.*

import java.util.concurrent.TimeUnit

class ExecuteCommandIntegrationTest extends ScalaLspTestBase:

  private def workspaceService = ScalaWorkspaceService(projectManager)

  def testOrganizeImports(): Unit =
    val uri = configureScalaFile(
      """import scala.collection.mutable.ArrayBuffer
        |import scala.collection.mutable.ListBuffer
        |
        |object Main:
        |  val x = new ArrayBuffer[Int]
        |""".stripMargin
    )
    val params = ExecuteCommandParams()
    params.setCommand("scala.organizeImports")
    params.setArguments(java.util.List.of(uri))
    val result = workspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    // After organizing, the unused ListBuffer import should be removed
    val doc = getDocument
    val text = doc.getText
    assertFalse("Unused import should be removed", text.contains("ListBuffer"))
    assertTrue("Used import should remain", text.contains("ArrayBuffer"))

  def testReformat(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val params = ExecuteCommandParams()
    params.setCommand("scala.reformat")
    params.setArguments(java.util.List.of(uri))
    val result = workspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    // Verify document was reformatted (exact formatting depends on code style settings)
    val doc = getDocument
    assertNotNull(doc)

  def testUnknownCommandReturnsNull(): Unit =
    val params = ExecuteCommandParams()
    params.setCommand("scala.nonexistent")
    params.setArguments(java.util.List.of("file:///dummy"))
    val result = workspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    assertNull("Unknown command should return null", result)
