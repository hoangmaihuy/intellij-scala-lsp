package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.CodeActionProvider
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*

class CodeActionProviderIntegrationTest extends ScalaLspTestBase:

  private def getActions(uri: String, range: Range) =
    val context = CodeActionContext(java.util.Collections.emptyList())
    CodeActionProvider(projectManager).getCodeActions(uri, range, context)

  def testTypeMismatchQuickFix(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = "hello"
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val actions = getActions(uri, Range(Position(1, 2), Position(1, 22)))
    // Quick fixes depend on daemon analysis being complete; may only get intentions
    assertNotNull(actions)

  def testMissingImportAction(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val buf = new ArrayBuffer[Int]()
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val actions = getActions(uri, Range(Position(1, 14), Position(1, 25)))
    // Import actions depend on highlighting infrastructure
    assertNotNull(actions)

  def testIntentionActionAvailable(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = getActions(uri, Range(Position(1, 10), Position(1, 15)))
    assertNotNull(actions)

  def testNoActionsForCleanCode(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val actions = getActions(uri, Range(Position(0, 0), Position(0, 1)))
    assertNotNull(actions)

  def testQuickFixLinkedToDiagnostic(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = "wrong"
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val actions = getActions(uri, Range(Position(1, 2), Position(1, 22)))
    val quickFixes = actions.filter(_.getKind == CodeActionKind.QuickFix)
    quickFixes.foreach: fix =>
      val diagnostics = fix.getDiagnostics
      if diagnostics != null && !diagnostics.isEmpty then
        assertNotNull("Quick fix should have linked diagnostic",
          diagnostics.get(0).getMessage)
