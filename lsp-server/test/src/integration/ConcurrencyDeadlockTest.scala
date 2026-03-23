package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.{ScalaTextDocumentService, TestLanguageClient}
import org.jetbrains.scalalsP.intellij.{DiagnosticsProvider, DocumentSyncManager, FormattingProvider}
import org.junit.Assert.*

import java.util.concurrent.{CompletableFuture, CountDownLatch, ExecutorService, Executors, TimeUnit, TimeoutException}
import scala.jdk.CollectionConverters.*

/**
 * Tests that the LSP server handles concurrent requests without deadlocking.
 *
 * These tests verify fixes for three deadlock patterns:
 * 1. FormattingProvider: invokeAndWait inside smartReadAction (read lock + write lock)
 * 2. ForkJoinPool exhaustion: all threads blocked in smartReadAction or invokeAndWait
 * 3. DocumentSyncManager: invokeAndWait blocking lsp4j threads while EDT is busy
 *
 * Each test fires multiple concurrent requests and asserts they all complete
 * within a timeout. A deadlock would cause a TimeoutException.
 */
class ConcurrencyDeadlockTest extends ScalaLspTestBase:

  private val TIMEOUT_SECONDS = 30L
  private val CONCURRENCY = 20

  private def newTextDocService(): ScalaTextDocumentService =
    val svc = ScalaTextDocumentService(projectManager, DiagnosticsProvider(projectManager))
    svc.connect(TestLanguageClient())
    svc

  private def definitionParams(uri: String, line: Int, char: Int): DefinitionParams =
    val params = DefinitionParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(line, char))
    params

  private def hoverParams(uri: String, line: Int, char: Int): HoverParams =
    val params = HoverParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(line, char))
    params

  private def referenceParams(uri: String, line: Int, char: Int): ReferenceParams =
    val params = ReferenceParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setPosition(Position(line, char))
    params.setContext(ReferenceContext(true))
    params

  private def symbolParams(uri: String): DocumentSymbolParams =
    val params = DocumentSymbolParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params

  private def formattingParams(uri: String): DocumentFormattingParams =
    val params = DocumentFormattingParams()
    params.setTextDocument(TextDocumentIdentifier(uri))
    params.setOptions(FormattingOptions(2, true))
    params

  private def pumpEdtUntilAllDone(futures: Seq[CompletableFuture[?]], timeoutSeconds: Long): Unit =
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    while futures.exists(!_.isDone) && System.currentTimeMillis() < deadline do
      try com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      catch case _: Exception => ()
      Thread.sleep(50)
    // Verify all completed (not just timed out)
    val incomplete = futures.count(!_.isDone)
    if incomplete > 0 then
      // Dump thread state for debugging
      val threadDump = Thread.getAllStackTraces.asScala
        .filter((t, _) => t.getName.contains("lsp") || t.getName.contains("ForkJoinPool") || t.getName.contains("AWT"))
        .map((t, stack) => s"${t.getName} (${t.getState}):\n${stack.take(10).map(s => s"  $s").mkString("\n")}")
        .mkString("\n\n")
      fail(s"$incomplete of ${futures.size} requests did not complete within ${timeoutSeconds}s (likely deadlock).\n\nRelevant threads:\n$threadDump")

  // ============================================================
  // Test 1: Concurrent read requests should not deadlock
  // Verifies: dedicated executor pool prevents ForkJoinPool exhaustion
  // ============================================================

  def testConcurrentReadRequestsNoDeadlock(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = x + 1
        |  def foo(a: Int): String = a.toString
        |  def bar = foo(x)
        |""".stripMargin
    )

    val svc = newTextDocService()

    // Fire many concurrent read requests of different types
    val futures = (1 to CONCURRENCY).map: i =>
      i % 4 match
        case 0 => svc.definition(definitionParams(uri, 2, 10))
        case 1 => svc.hover(hoverParams(uri, 1, 6))
        case 2 => svc.references(referenceParams(uri, 1, 6))
        case 3 => svc.documentSymbol(symbolParams(uri))

    pumpEdtUntilAllDone(futures, TIMEOUT_SECONDS)

    // All completed — verify at least one returned meaningful results
    val defResult = svc.definition(definitionParams(uri, 2, 10)).get(10, TimeUnit.SECONDS)
    assertNotNull("Definition should return result after concurrent requests", defResult)

  // ============================================================
  // Test 2: Formatting concurrent with read requests should not deadlock
  // Verifies: formatOnCopy runs outside smartReadAction, so invokeAndWait
  //           for WriteCommandAction doesn't hold read lock
  // ============================================================

  def testFormattingConcurrentWithReadsNoDeadlock(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |def bar = {
        |   val y = "hello"
        |  y.length
        |}
        |}
        |""".stripMargin
    )

    val svc = newTextDocService()

    // Interleave formatting requests with read requests
    val futures = (1 to CONCURRENCY).map: i =>
      i % 5 match
        case 0 => svc.formatting(formattingParams(uri))
        case 1 => svc.definition(definitionParams(uri, 3, 0))
        case 2 => svc.hover(hoverParams(uri, 2, 4))
        case 3 => svc.documentSymbol(symbolParams(uri))
        case 4 => svc.references(referenceParams(uri, 2, 4))

    pumpEdtUntilAllDone(futures, TIMEOUT_SECONDS)

  // ============================================================
  // Test 3: Document sync interleaved with requests should not deadlock
  // Verifies: invokeLater in DocumentSyncManager doesn't block lsp4j threads
  // ============================================================

  def testDocSyncInterleavedWithRequestsNoDeadlock(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |""".stripMargin
    )

    val svc = newTextDocService()

    // Simulate rapid didChange + concurrent read requests
    val futures = (1 to CONCURRENCY).map: i =>
      if i % 3 == 0 then
        // Synchronous didChange notification
        val changeParams = DidChangeTextDocumentParams()
        val id = VersionedTextDocumentIdentifier()
        id.setUri(uri)
        id.setVersion(i)
        changeParams.setTextDocument(id)
        val change = TextDocumentContentChangeEvent()
        change.setText(s"""object Main:
          |  val x = ${40 + i}
          |  def foo = x + 1
          |""".stripMargin)
        changeParams.setContentChanges(java.util.List.of(change))
        svc.didChange(changeParams)
        // Return a completed future for uniformity
        CompletableFuture.completedFuture(null)
      else
        i % 3 match
          case 1 => svc.hover(hoverParams(uri, 1, 6))
          case 2 => svc.definition(definitionParams(uri, 2, 12))

    pumpEdtUntilAllDone(futures, TIMEOUT_SECONDS)

  // ============================================================
  // Test 4: Many concurrent formatting requests should not deadlock
  // Verifies: multiple threads calling formatOnCopy (invokeAndWait)
  //           concurrently don't block each other via EDT contention
  // ============================================================

  def testManyConcurrentFormattingRequestsNoDeadlock(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )

    val svc = newTextDocService()

    // Fire many formatting requests simultaneously
    val futures = (1 to 10).map: _ =>
      svc.formatting(formattingParams(uri))

    pumpEdtUntilAllDone(futures, TIMEOUT_SECONDS)

    // Verify formatting still works correctly after concurrent burst
    val result = futures.head.get(1, TimeUnit.SECONDS)
    assertNotNull("Formatting should return result", result)

  // ============================================================
  // Test 5: Mixed read/write operations from multiple service instances
  // Verifies: separate service instances sharing the same project don't deadlock
  // ============================================================

  def testMultipleServiceInstancesConcurrentNoDeadlock(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x.toString
        |  def bar = foo.length
        |""".stripMargin
    )

    // Create multiple service instances (simulates daemon mode with multiple sessions)
    val services = (1 to 3).map(_ => newTextDocService())

    val futures = services.flatMap: svc =>
      Seq(
        svc.definition(definitionParams(uri, 2, 10)),
        svc.hover(hoverParams(uri, 1, 6)),
        svc.documentSymbol(symbolParams(uri)),
        svc.references(referenceParams(uri, 1, 6)),
      )

    pumpEdtUntilAllDone(futures, TIMEOUT_SECONDS)

  // ============================================================
  // Test 6: FormattingProvider directly — verifies the specific deadlock fix
  // Verifies: formatOnCopy is called outside smartReadAction
  // ============================================================

  def testFormattingProviderDirectConcurrentNoDeadlock(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )

    val provider = FormattingProvider(projectManager)
    val executor = Executors.newFixedThreadPool(8)

    try
      // Fire concurrent formatting from a thread pool
      val futures = (1 to 10).map: _ =>
        CompletableFuture.supplyAsync((() => provider.getFormatting(uri)): java.util.function.Supplier[Seq[TextEdit]], executor)

      pumpEdtUntilAllDone(futures, TIMEOUT_SECONDS)

      // Verify correctness
      val edits = futures.head.get(1, TimeUnit.SECONDS)
      assertFalse("Formatting should produce edits for unformatted code", edits.isEmpty)
    finally
      executor.shutdown()
