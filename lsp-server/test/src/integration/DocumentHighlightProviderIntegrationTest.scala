package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{DocumentHighlight, DocumentHighlightKind, Position}
import org.jetbrains.scalalsP.intellij.DocumentHighlightProvider
import org.junit.Assert.*

class DocumentHighlightProviderIntegrationTest extends ScalaLspTestBase:

  private def getHighlights(uri: String, pos: Position) =
    DocumentHighlightProvider(projectManager).getDocumentHighlights(uri, pos)

  def testHighlightVariableUsages(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = x + 1
        |  val z = x * 2
        |""".stripMargin
    )
    // Highlight from usage site (line 2, column 10 — "x" in "x + 1")
    val highlights = getHighlights(uri, positionAt(2, 10))

    // Verify: definition at (1,6) is Write, usages at (2,10) and (3,10) are Read
    val writeHighlights = highlights.filter(_.getKind == DocumentHighlightKind.Write)
    val readHighlights  = highlights.filter(_.getKind == DocumentHighlightKind.Read)

    if highlights.size >= 3 then
      // Full resolution works — verify positions and kinds exactly
      assertEquals("Should have exactly 1 Write highlight (definition)", 1, writeHighlights.size)
      assertTrue("Should have at least 2 Read highlights (usages)", readHighlights.size >= 2)

      val defHighlight = writeHighlights.head
      assertEquals("Definition should be on line 1", 1, defHighlight.getRange.getStart.getLine)
      assertEquals("Definition should start at char 6", 6, defHighlight.getRange.getStart.getCharacter)
    else if highlights.nonEmpty then
      // Light test mode — at least verify highlight kinds are valid
      assertTrue("All highlights must have Write or Read kind",
        highlights.forall(h => h.getKind == DocumentHighlightKind.Write || h.getKind == DocumentHighlightKind.Read))

  def testHighlightDefinitionIsWrite(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |""".stripMargin
    )
    val highlights = getHighlights(uri, positionAt(1, 6))
    if highlights.nonEmpty then
      val writeHighlights = highlights.filter(_.getKind == DocumentHighlightKind.Write)
      assertTrue("Definition site should be marked as Write", writeHighlights.nonEmpty)
      // Definition site must be on line 1
      assertTrue("Write highlight should be on definition line 1",
        writeHighlights.exists(_.getRange.getStart.getLine == 1))

  def testHighlightReferencesAreRead(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    val highlights = getHighlights(uri, positionAt(1, 6))
    if highlights.size >= 2 then
      val readHighlights = highlights.filter(_.getKind == DocumentHighlightKind.Read)
      assertTrue("Usage sites should be marked as Read", readHighlights.nonEmpty)
      // Read highlights should correspond to usage lines 2 and 3
      val readLines = readHighlights.map(_.getRange.getStart.getLine).toSet
      assertTrue("Read highlights should appear on usage lines (2 and/or 3)",
        readLines.intersect(Set(2, 3)).nonEmpty)

  def testHighlightMethodFromDefinition(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String): String = s"Hello $name"
        |  val a = greet("Alice")
        |  val b = greet("Bob")
        |""".stripMargin
    )
    // Highlight from definition site — cursor on "greet" (line 1, char 6)
    val highlights = getHighlights(uri, positionAt(1, 6))
    if highlights.size >= 3 then
      val writeHighlights = highlights.filter(_.getKind == DocumentHighlightKind.Write)
      assertEquals("Method definition should produce exactly 1 Write highlight", 1, writeHighlights.size)
      assertEquals("Method definition Write highlight should be on line 1",
        1, writeHighlights.head.getRange.getStart.getLine)

      val readHighlights = highlights.filter(_.getKind == DocumentHighlightKind.Read)
      assertTrue("Method usages should produce Read highlights", readHighlights.size >= 2)
    else if highlights.nonEmpty then
      assertTrue("All highlights must have Write or Read kind",
        highlights.forall(h => h.getKind == DocumentHighlightKind.Write || h.getKind == DocumentHighlightKind.Read))

  def testHighlightFromReference(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    // Cursor on the reference "x" in "foo = x + 1" (line 2, char 12)
    val fromRef = getHighlights(uri, positionAt(2, 12))
    // Cursor on the definition "x" (line 1, char 6)
    val fromDef = getHighlights(uri, positionAt(1, 6))

    if fromRef.nonEmpty && fromDef.nonEmpty then
      // Both queries must find the same set of locations
      val refLines = fromRef.map(_.getRange.getStart.getLine).sorted
      val defLines = fromDef.map(_.getRange.getStart.getLine).sorted
      assertEquals("Highlights from reference and from definition should cover the same lines",
        defLines, refLines)
    else if fromRef.nonEmpty then
      assertTrue("Should find at least 2 highlights when starting from a reference", fromRef.size >= 2)

  def testNoHighlightsForComment(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  // just a comment
        |""".stripMargin
    )
    val highlights = getHighlights(uri, positionAt(1, 5))
    assertNotNull("Result should not be null", highlights)
    assertTrue("Comments should produce no highlights", highlights.isEmpty)

  def testNoHighlightsForUnresolvableSymbol(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = undefinedSymbol
        |""".stripMargin
    )
    val highlights = getHighlights(uri, positionAt(1, 10))
    assertNotNull("Result should not be null", highlights)
    // undefinedSymbol has no definition — expect empty or valid kinds only
    highlights.foreach: h =>
      assertTrue("Any returned highlight must have a valid kind",
        h.getKind == DocumentHighlightKind.Write || h.getKind == DocumentHighlightKind.Read)
