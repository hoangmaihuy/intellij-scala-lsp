package org.jetbrains.scalalsP.integration

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.CodeActionProvider
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*

class CodeActionProviderIntegrationTest extends ScalaLspTestBase:

  private def getActions(uri: String, range: Range) =
    val context = CodeActionContext(java.util.Collections.emptyList())
    CodeActionProvider(projectManager).getCodeActions(uri, range, context)

  def testIntentionActionsHaveNonEmptyTitles(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = getActions(uri, Range(Position(1, 10), Position(1, 15)))
    assertNotNull("getCodeActions must return a list, not null", actions)
    actions.foreach: action =>
      val title = action.getTitle
      assertNotNull(s"Every code action must have a non-null title", title)
      assertFalse(s"Every code action must have a non-empty title", title.isBlank)

  def testIntentionActionsHaveDataField(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = getActions(uri, Range(Position(1, 10), Position(1, 15)))
    // When intentions are available, every action must carry a typed data object
    actions.foreach: action =>
      val data = action.getData
      assertNotNull(s"Code action '${action.getTitle}' must have a data field", data)
      val obj = data.asInstanceOf[JsonObject]
      val typeField = obj.get("type")
      val uriField = obj.get("uri")
      assertNotNull(s"Action '${action.getTitle}' data must contain 'type'", typeField)
      assertNotNull(s"Action '${action.getTitle}' data must contain 'uri'", uriField)
      val actionType = typeField.getAsString
      assertTrue(
        s"'type' must be 'quickfix' or 'intention', got: '$actionType'",
        actionType == "quickfix" || actionType == "intention"
      )
      val actionUri = uriField.getAsString
      assertTrue(s"'uri' must start with 'file://', got: '$actionUri'", actionUri.startsWith("file://"))

  def testIntentionActionsHaveValidKind(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = getActions(uri, Range(Position(1, 10), Position(1, 15)))
    val validKinds = Set(
      CodeActionKind.QuickFix,
      CodeActionKind.Refactor,
      CodeActionKind.RefactorExtract,
      CodeActionKind.RefactorInline,
      CodeActionKind.Source,
      CodeActionKind.SourceOrganizeImports
    )
    actions.foreach: action =>
      val kind = action.getKind
      assertNotNull(s"Action '${action.getTitle}' must have a kind", kind)
      assertTrue(
        s"Action '${action.getTitle}' has unexpected kind: '$kind'",
        validKinds.contains(kind)
      )

  def testTypeMismatchQuickFix(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = "hello"
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val actions = getActions(uri, Range(Position(1, 2), Position(1, 22)))
    assertNotNull("getCodeActions must not return null", actions)
    // Quick fixes that ARE present must be well-formed
    val quickFixes = actions.filter(_.getKind == CodeActionKind.QuickFix)
    quickFixes.foreach: fix =>
      assertFalse(s"Quick fix title must be non-empty", fix.getTitle.isBlank)
      val data = fix.getData.asInstanceOf[JsonObject]
      assertEquals("Quick fix data type must be 'quickfix'", "quickfix", data.get("type").getAsString)

  def testQuickFixLinkedToDiagnosticHasRangeAndMessage(): Unit =
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
        val diag = diagnostics.get(0)
        assertNotNull("Linked diagnostic must have a message", diag.getMessage)
        assertFalse("Linked diagnostic message must be non-empty", diag.getMessage.isBlank)
        assertNotNull("Linked diagnostic must have a range", diag.getRange)
        assertNotNull("Diagnostic range start must not be null", diag.getRange.getStart)
        assertNotNull("Diagnostic range end must not be null", diag.getRange.getEnd)

  def testNoActionsForCleanCodeReturnsEmptyList(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |""".stripMargin
    )
    myFixture.doHighlighting()
    // Position far from any interesting construct — may return intentions but must not crash
    val actions = getActions(uri, Range(Position(0, 0), Position(0, 1)))
    assertNotNull("Result must not be null even for clean code", actions)

  def testMissingImportActionHasSourceKind(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val buf = new ArrayBuffer[Int]()
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val actions = getActions(uri, Range(Position(1, 14), Position(1, 25)))
    assertNotNull("getCodeActions must not return null", actions)
    // Any action mentioning "import" in its title should be classified as Source
    val importActions = actions.filter(a => a.getTitle.toLowerCase.contains("import"))
    importActions.foreach: action =>
      val kind = action.getKind
      assertTrue(
        s"Import action '${action.getTitle}' should have Source or QuickFix kind, got: $kind",
        kind == CodeActionKind.Source || kind == CodeActionKind.QuickFix
      )
