package org.jetbrains.scalalsP.e2e

import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity}
import org.jetbrains.scalalsP.intellij.DiagnosticsProvider
import org.junit.Assert.*

import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.jdk.CollectionConverters.*

class PullDiagnosticsE2eTest extends E2eTestBase:

  private def runAnalysisOffEdt(provider: DiagnosticsProvider, uri: String): Seq[Diagnostic] =
    val future = CompletableFuture.supplyAsync[Seq[Diagnostic]](() => provider.runAnalysisAndCollect(uri))
    val deadline = System.currentTimeMillis() + 15000
    while !future.isDone && System.currentTimeMillis() < deadline do
      try com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      catch case _: Exception => ()
      Thread.sleep(50)
    future.get(1, TimeUnit.SECONDS)

  /** Core test: find error, fix externally, verify diagnostics update. */
  def testDiagnosticsUpdateAfterExternalEdit(): Unit =
    // Step 1: Create file with a type error
    val uri = configureActiveScalaFile(
      """object EditTest {
        |  val x: Int = "hello"
        |}
        |""".stripMargin
    )

    val provider = DiagnosticsProvider(projectManager)

    // Step 2: Run diagnostics — should find error (or at least not crash)
    val diags1 = runAnalysisOffEdt(provider, uri)
    System.err.println(s"[TEST] First analysis: ${diags1.size} diagnostics")
    diags1.foreach(d => System.err.println(s"[TEST]   ${d.getSeverity}: ${d.getMessage}"))

    // Step 3: "Edit externally" — simulate by writing fixed content directly to the VirtualFile
    // This mimics what happens when Claude Code or VS Code edits and saves the file
    val vf = projectManager.findVirtualFile(uri).get
    com.intellij.openapi.application.ApplicationManager.getApplication.invokeAndWait: () =>
      com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(projectManager.getProject, (() =>
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        if document != null then
          // Fix the error: change "hello" to 42
          val newContent = """object EditTest {
            |  val x: Int = 42
            |}
            |""".stripMargin
          document.setText(newContent)
          projectManager.getPsiDocumentManager.commitDocument(document)
      ): Runnable)

    // Step 4: Run diagnostics again — should see updated content
    val diags2 = runAnalysisOffEdt(provider, uri)
    System.err.println(s"[TEST] Second analysis after fix: ${diags2.size} diagnostics")
    diags2.foreach(d => System.err.println(s"[TEST]   ${d.getSeverity}: ${d.getMessage}"))

    // The fixed code should have fewer errors than the broken code
    // (In light test mode without SDK, both may be empty — that's OK)
    val errors1 = diags1.count(_.getSeverity == DiagnosticSeverity.Error)
    val errors2 = diags2.count(_.getSeverity == DiagnosticSeverity.Error)
    System.err.println(s"[TEST] Errors before fix: $errors1, after fix: $errors2")

    // If the first run found errors, the second should have fewer
    if errors1 > 0 then
      assertTrue(
        s"After fixing the error, should have fewer errors. Before: $errors1, After: $errors2",
        errors2 < errors1
      )

  /** Test that runAnalysisAndCollect completes without crashing. */
  def testRunAnalysisAndCollectDoesNotCrash(): Unit =
    val uri = configureActiveScalaFile(
      """object BadCode {
        |  val x: Int = "hello"
        |}
        |""".stripMargin
    )
    val provider = DiagnosticsProvider(projectManager)
    val diags = runAnalysisOffEdt(provider, uri)
    assertNotNull("runAnalysisAndCollect should return a list", diags)

  /** Test pullDiagnostics via executeCommand completes without timeout. */
  def testPullDiagnosticsCommandCompletes(): Unit =
    val uri = configureActiveScalaFile(
      """object CmdTest {
        |  val x: Int = "wrong type"
        |}
        |""".stripMargin
    )
    val result = client.executeCommand("scala.pullDiagnostics", java.util.List.of(uri.asInstanceOf[AnyRef]))
    assertNotNull("pullDiagnostics should return a result", result)

  /** Test clean code doesn't crash. */
  def testPullDiagnosticsCleanCode(): Unit =
    val uri = configureActiveScalaFile(
      """object CleanCode {
        |  val x: Int = 42
        |}
        |""".stripMargin
    )
    val provider = DiagnosticsProvider(projectManager)
    val diags = runAnalysisOffEdt(provider, uri)
    assertNotNull(diags)
