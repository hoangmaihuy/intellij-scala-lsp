package org.jetbrains.scalalsP

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.*
import org.junit.Ignore

import scala.jdk.CollectionConverters.*

// Integration tests that boot a lightweight IntelliJ application and test
// providers against real PSI. Extends BasePlatformTestCase to get an in-memory
// project with configureByText support.
//
// These tests require the full IntelliJ platform to be bootstrapped with plugin
// loading. Run them with: ./mill lsp-server.test -t "LspIntegrationTest"
// after setting up the test environment (see intellij-scala's test setup).
//
// Ignored by default in CI/normal builds — remove @Ignore to run locally
// with a properly configured IntelliJ test environment.
@Ignore("Requires full IntelliJ platform bootstrap — run manually with proper test environment")
class LspIntegrationTest extends BasePlatformTestCase:

  import org.junit.Assert.*

  private def configureScala(text: String): Unit =
    myFixture.configureByText("Test.scala", text)

  private def getUri: String =
    PsiUtils.vfToUri(myFixture.getFile.getVirtualFile)

  private def getDocument: com.intellij.openapi.editor.Document =
    FileDocumentManager.getInstance().getDocument(myFixture.getFile.getVirtualFile)

  // --- PsiUtils integration ---

  def testPositionToOffsetAndBack(): Unit =
    configureScala("object Main:\n  val x = 42\n")
    val doc = getDocument
    // "val" starts at line 1, char 2
    val offset = PsiUtils.positionToOffset(doc, new Position(1, 2))
    val pos = PsiUtils.offsetToPosition(doc, offset)
    assertEquals(1, pos.getLine)
    assertEquals(2, pos.getCharacter)

  // --- DefinitionProvider integration ---

  def testDefinitionResolvesLocalVal(): Unit =
    // Place caret on the reference to `x` in `println(x)`
    configureScala(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    val pm = new IntellijProjectManager()
    // Inject the test project
    setProject(pm)
    val provider = new DefinitionProvider(pm)
    val uri = getUri

    // "x" in "def foo = x" is at line 2, char 12
    val result = provider.getDefinition(uri, new Position(2, 12))
    assertFalse("Should find definition of x", result.isEmpty)
    // Definition should point to line 1 where "val x" is declared
    val defLocation = result.head
    assertEquals(uri, defLocation.getUri)
    assertEquals(1, defLocation.getRange.getStart.getLine)

  // --- ReferencesProvider integration ---

  def testFindReferencesToVal(): Unit =
    configureScala(
      """object Main:
        |  val x = 42
        |  def foo = x
        |  def bar = x + 1
        |""".stripMargin
    )
    val pm = new IntellijProjectManager()
    setProject(pm)
    val provider = new ReferencesProvider(pm)
    val uri = getUri

    // Find references to "x" declared at line 1, char 6
    val result = provider.findReferences(uri, new Position(1, 6), false)
    assertTrue("Should find references to x", result.size >= 2)

  // --- HoverProvider integration ---

  def testHoverReturnsContent(): Unit =
    configureScala(
      """object Main:
        |  val x: Int = 42
        |""".stripMargin
    )
    val pm = new IntellijProjectManager()
    setProject(pm)
    val provider = new HoverProvider(pm)
    val uri = getUri

    // Hover on "x" at line 1, char 6
    val result = provider.getHover(uri, new Position(1, 6))
    // May or may not produce hover depending on Scala plugin availability
    // but should not throw
    // If Scala plugin is loaded, we expect some content
    result match
      case Some(hover) =>
        assertNotNull(hover.getContents)
      case None =>
        // OK - Scala plugin might not be fully loaded in light test
        ()

  // --- SymbolProvider integration (documentSymbol) ---

  def testDocumentSymbolsFindsDeclarations(): Unit =
    configureScala(
      """object Main:
        |  val x = 42
        |  def foo(y: Int): Int = y + x
        |  class Inner:
        |    val z = 1
        |""".stripMargin
    )
    val pm = new IntellijProjectManager()
    setProject(pm)
    val provider = new SymbolProvider(pm)
    val uri = getUri

    val symbols = provider.documentSymbols(uri)
    // Should find at least Main object
    assertFalse("Should find document symbols", symbols.isEmpty)
    val names = flattenSymbolNames(symbols)
    assertTrue("Should find Main", names.contains("Main"))

  // --- SelectionRangeProvider integration ---

  def testSelectionRangeWalksPsiTree(): Unit =
    configureScala(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val pm = new IntellijProjectManager()
    setProject(pm)
    val provider = new SelectionRangeProvider(pm)
    val uri = getUri

    // Position on "42" at line 1, char 10
    val result = provider.getSelectionRanges(uri, Seq(new Position(1, 10)))
    assertFalse("Should return selection ranges", result.isEmpty)
    val sr = result.head
    assertNotNull("Selection range should not be null", sr)
    assertNotNull("Should have range", sr.getRange)
    // The innermost range should cover "42", parent should be broader
    if sr.getParent != null then
      assertTrue("Parent range should be wider",
        sr.getParent.getRange.getEnd.getLine >= sr.getRange.getEnd.getLine ||
        sr.getParent.getRange.getEnd.getCharacter >= sr.getRange.getEnd.getCharacter)

  // --- FoldingRangeProvider integration ---

  def testFoldingRangesForMultiLineBody(): Unit =
    configureScala(
      """object Main:
        |  def foo(): Unit =
        |    val x = 1
        |    val y = 2
        |    println(x + y)
        |
        |  def bar(): Unit =
        |    println("bar")
        |""".stripMargin
    )
    val pm = new IntellijProjectManager()
    setProject(pm)
    val provider = new FoldingRangeProvider(pm)
    val uri = getUri

    val ranges = provider.getFoldingRanges(uri)
    // Folding builder may or may not produce results depending on language plugin
    // but the call should succeed without error
    // If the Scala folding builder is loaded, we expect at least one range
    for range <- ranges do
      assertTrue("Folding range should span lines", range.getEndLine > range.getStartLine)

  // --- Helpers ---

  private def setProject(pm: IntellijProjectManager): Unit =
    // Use reflection to set the private project field from the test fixture's project
    val field = classOf[IntellijProjectManager].getDeclaredField("project")
    field.setAccessible(true)
    field.set(pm, getProject)

  private def flattenSymbolNames(symbols: Seq[DocumentSymbol]): Seq[String] =
    symbols.flatMap: s =>
      val children = Option(s.getChildren).map(_.asScala.toSeq).getOrElse(Seq.empty)
      s.getName +: flattenSymbolNames(children.map(_.asInstanceOf[DocumentSymbol]))
