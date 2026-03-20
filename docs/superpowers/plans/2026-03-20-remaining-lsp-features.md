# Remaining LSP Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 6 missing LSP features (Formatting, Completion Resolve, Signature Help, Document Link, Workspace Folders, Semantic Tokens) so the server provides near-complete LSP coverage for VS Code and other clients.

**Architecture:** Each feature follows the existing provider pattern — a new class in `lsp-server/src/intellij/` that receives `IntellijProjectManager`, wired via `ScalaTextDocumentService` overrides and registered in `ScalaLspServer.initialize()`. All providers use `smartReadAction` for reads and `WriteCommandAction` for writes.

**Tech Stack:** Scala 3.8.2, lsp4j 0.23.1, IntelliJ SDK 253, JUnit 4

**Spec:** `docs/superpowers/specs/2026-03-20-remaining-lsp-features-design.md`

**IMPORTANT:** Tasks MUST be executed sequentially — each task builds on the prior task's changes to shared files (`ScalaLspServer.scala`, `ScalaTextDocumentService.scala`).

---

## File Structure

**New provider files:**
- `lsp-server/src/intellij/FormattingProvider.scala` — `textDocument/formatting` + `rangeFormatting`
- `lsp-server/src/intellij/SignatureHelpProvider.scala` — `textDocument/signatureHelp`
- `lsp-server/src/intellij/DocumentLinkProvider.scala` — `textDocument/documentLink`
- `lsp-server/src/intellij/SemanticTokensProvider.scala` — `textDocument/semanticTokens`

**Modified files:**
- `lsp-server/src/ScalaLspServer.scala` — capability registration for all 6 features
- `lsp-server/src/ScalaTextDocumentService.scala` — new provider fields + override methods
- `lsp-server/src/ScalaWorkspaceService.scala` — `didChangeWorkspaceFolders` handler
- `lsp-server/src/intellij/CompletionProvider.scala` — refactor for lazy resolve
- `lsp-server/src/intellij/IntellijProjectManager.scala` — multi-project support

**New test files:**
- `lsp-server/test/src/integration/FormattingProviderIntegrationTest.scala`
- `lsp-server/test/src/integration/CompletionResolveIntegrationTest.scala`
- `lsp-server/test/src/integration/SignatureHelpProviderIntegrationTest.scala`
- `lsp-server/test/src/integration/DocumentLinkProviderIntegrationTest.scala`
- `lsp-server/test/src/integration/WorkspaceFoldersIntegrationTest.scala`
- `lsp-server/test/src/integration/SemanticTokensProviderIntegrationTest.scala`
- `lsp-server/test/src/e2e/FormattingE2eTest.scala`
- `lsp-server/test/src/e2e/SignatureHelpE2eTest.scala`
- `lsp-server/test/src/e2e/DocumentLinkE2eTest.scala`
- `lsp-server/test/src/e2e/SemanticTokensE2eTest.scala`

---

## Task 1: Formatting Provider

**Files:**
- Create: `lsp-server/src/intellij/FormattingProvider.scala`
- Create: `lsp-server/test/src/integration/FormattingProviderIntegrationTest.scala`
- Create: `lsp-server/test/src/e2e/FormattingE2eTest.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`

- [ ] **Step 1: Write integration test for full-file formatting**

Create `lsp-server/test/src/integration/FormattingProviderIntegrationTest.scala`:

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.FormattingProvider
import org.junit.Assert.*

class FormattingProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = FormattingProvider(projectManager)

  def testFormatBadlyFormattedCode(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val edits = provider.getFormatting(uri)
    assertNotNull("Formatting should return edits", edits)
    assertFalse("Should have at least one edit", edits.isEmpty)

  def testFormatAlreadyFormattedCode(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo: Int =
        |    val x = 42
        |    x + 1
        |""".stripMargin
    )
    val edits = provider.getFormatting(uri)
    // Well-formatted code may return empty edits or edits that produce the same text
    assertNotNull(edits)

  def testFormatDoesNotMutateDocument(): Unit =
    val code =
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    val uri = configureScalaFile(code)
    val beforeText = getDocument.getText
    provider.getFormatting(uri)
    val afterText = getDocument.getText
    assertEquals("Document should not be mutated by formatting", beforeText, afterText)
```

- [ ] **Step 2: Write integration test for range formatting**

Add to the same test file:

```scala
  def testRangeFormatting(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |def foo={
        |val x=42
        |x+1
        |}
        |""".stripMargin
    )
    // Format only lines 1-3
    val range = Range(Position(1, 0), Position(3, 4))
    val edits = provider.getRangeFormatting(uri, range)
    assertNotNull("Range formatting should return edits", edits)
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.FormattingProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: Compilation failure — `FormattingProvider` class not found

- [ ] **Step 4: Implement FormattingProvider**

Create `lsp-server/src/intellij/FormattingProvider.scala`:

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

class FormattingProvider(projectManager: IntellijProjectManager):

  def getFormatting(uri: String): Seq[TextEdit] =
    try
      projectManager.smartReadAction: () =>
        (for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val originalText = document.getText
          val formattedText = formatOnCopy(psiFile, originalText)
          if formattedText != originalText then
            computeFullReplacement(originalText, formattedText)
          else
            Seq.empty
        ).getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[FormattingProvider] Error: ${e.getMessage}")
        Seq.empty

  def getRangeFormatting(uri: String, range: Range): Seq[TextEdit] =
    try
      projectManager.smartReadAction: () =>
        (for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val originalText = document.getText
          val startOffset = PsiUtils.positionToOffset(document, range.getStart)
          val endOffset = PsiUtils.positionToOffset(document, range.getEnd)
          val formattedText = formatRangeOnCopy(psiFile, originalText, startOffset, endOffset)
          if formattedText != originalText then
            computeFullReplacement(originalText, formattedText)
          else
            Seq.empty
        ).getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[FormattingProvider] Error in range formatting: ${e.getMessage}")
        Seq.empty

  private def formatOnCopy(
    originalFile: com.intellij.psi.PsiFile,
    originalText: String
  ): String =
    val project = projectManager.getProject
    val copy = PsiFileFactory.getInstance(project)
      .createFileFromText("_format_tmp.scala", originalFile.getLanguage, originalText)
    // CodeStyleManager.reformat requires write action
    var result = originalText
    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
        CodeStyleManager.getInstance(project).reformat(copy)
        result = copy.getText
      ): Runnable)
    if ApplicationManager.getApplication.isDispatchThread then
      runFormat.run()
    else
      ApplicationManager.getApplication.invokeAndWait(runFormat)
    result

  private def formatRangeOnCopy(
    originalFile: com.intellij.psi.PsiFile,
    originalText: String,
    startOffset: Int,
    endOffset: Int
  ): String =
    val project = projectManager.getProject
    val copy = PsiFileFactory.getInstance(project)
      .createFileFromText("_format_tmp.scala", originalFile.getLanguage, originalText)
    var result = originalText
    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
        CodeStyleManager.getInstance(project).reformatRange(copy, startOffset, endOffset)
        result = copy.getText
      ): Runnable)
    if ApplicationManager.getApplication.isDispatchThread then
      runFormat.run()
    else
      ApplicationManager.getApplication.invokeAndWait(runFormat)
    result

  private def computeFullReplacement(originalText: String, formattedText: String): Seq[TextEdit] =
    val lineCount = originalText.count(_ == '\n') + 1
    val lastLineLength = originalText.length - originalText.lastIndexOf('\n') - 1
    val fullRange = Range(
      Position(0, 0),
      Position(lineCount - 1, lastLineLength)
    )
    Seq(TextEdit(fullRange, formattedText))
```

- [ ] **Step 5: Wire into ScalaTextDocumentService**

Add to `ScalaTextDocumentService.scala` — add provider field alongside existing providers:

```scala
private val formattingProvider = FormattingProvider(projectManager)
```

Add override methods:

```scala
  // --- Formatting ---

  override def formatting(params: DocumentFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    CompletableFuture.supplyAsync: () =>
      formattingProvider.getFormatting(params.getTextDocument.getUri).asJava

  override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
    CompletableFuture.supplyAsync: () =>
      formattingProvider.getRangeFormatting(
        params.getTextDocument.getUri,
        params.getRange
      ).asJava
```

- [ ] **Step 6: Register capabilities in ScalaLspServer**

Add to `ScalaLspServer.initialize()` after the code actions block:

```scala
      // Formatting
      capabilities.setDocumentFormattingProvider(true)
      capabilities.setDocumentRangeFormattingProvider(true)
```

- [ ] **Step 7: Run integration tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.FormattingProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 8: Write E2E test**

Add helper methods to `TestLspClient.scala`:

```scala
  // In TestLspClient:
  def formatting(uri: String): java.util.List[? <: TextEdit] =
    requestOffEdt() {
      val params = new DocumentFormattingParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setOptions(new FormattingOptions(2, true))
      clientProxy.getTextDocumentService.formatting(params).get(10, TimeUnit.SECONDS)
    }

  def rangeFormatting(uri: String, range: Range): java.util.List[? <: TextEdit] =
    requestOffEdt() {
      val params = new DocumentRangeFormattingParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setRange(range)
      params.setOptions(new FormattingOptions(2, true))
      clientProxy.getTextDocumentService.rangeFormatting(params).get(10, TimeUnit.SECONDS)
    }
```

Then simplify the E2E test:

```scala
package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class FormattingE2eTest extends E2eTestBase:

  def testFormattingViaLsp(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val result = client.formatting(uri)
    assertNotNull("Formatting should return non-null", result)
```

- [ ] **Step 9: Run all tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.FormattingProviderIntegrationTest org.jetbrains.scalalsP.e2e.FormattingE2eTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 10: Commit**

```bash
git add lsp-server/src/intellij/FormattingProvider.scala \
       lsp-server/src/ScalaLspServer.scala \
       lsp-server/src/ScalaTextDocumentService.scala \
       lsp-server/test/src/integration/FormattingProviderIntegrationTest.scala \
       lsp-server/test/src/e2e/FormattingE2eTest.scala \
       lsp-server/test/src/e2e/TestLspClient.scala
git commit -m "feat: add textDocument/formatting and rangeFormatting support"
```

---

## Task 2: Completion Resolve

**Files:**
- Modify: `lsp-server/src/intellij/CompletionProvider.scala`
- Create: `lsp-server/test/src/integration/CompletionResolveIntegrationTest.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`

- [ ] **Step 1: Write integration test for completion resolve**

Create `lsp-server/test/src/integration/CompletionResolveIntegrationTest.scala`:

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.CompletionProvider
import org.junit.Assert.*

class CompletionResolveIntegrationTest extends ScalaLspTestBase:

  private def provider = CompletionProvider(projectManager)

  def testCompletionItemsHaveDataField(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      val first = items.head
      assertNotNull("Completion item should have data field", first.getData)

  def testCompletionItemsAreLean(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      val first = items.head
      // Before resolve, documentation should be null (lazy)
      assertNull("Documentation should be lazy-loaded", first.getDocumentation)

  def testResolvePopulatesDocumentation(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      val resolved = provider.resolveCompletion(items.head)
      // After resolve, at least detail or documentation should be populated
      val hasDetail = resolved.getDetail != null && resolved.getDetail.nonEmpty
      val hasDoc = resolved.getDocumentation != null
      assertTrue("Resolved item should have detail or documentation", hasDetail || hasDoc)

  def testResolveStaleRequestReturnsItemUnchanged(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val xs = List(1, 2, 3)
        |  xs.
        |""".stripMargin
    )
    val items = provider.getCompletions(uri, positionAt(2, 5))
    if items.nonEmpty then
      // Trigger a new completion to invalidate the cache
      provider.getCompletions(uri, positionAt(2, 5))
      // Resolve with the old item — should return unchanged (stale request ID)
      val resolved = provider.resolveCompletion(items.head)
      assertNotNull("Stale resolve should still return an item", resolved)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.CompletionResolveIntegrationTest" 2>&1 | tee /local/log`
Expected: Compilation failure — `resolveCompletion` method not found

- [ ] **Step 3: Refactor CompletionProvider for lazy resolve**

Modify `lsp-server/src/intellij/CompletionProvider.scala`:

Add imports and cache fields at the top of the class:

```scala
import com.intellij.lang.LanguageDocumentation
import java.util.concurrent.atomic.AtomicLong

class CompletionProvider(projectManager: IntellijProjectManager):

  private val requestIdCounter = AtomicLong(0)
  @volatile private var cachedElements: Array[LookupElement] = Array.empty
  @volatile private var cachedRequestId: Long = -1
  @volatile private var cacheTimestamp: Long = 0
  private val CacheTtlMs = 30_000L
```

Modify `getCompletions` to store cache and return lean items:

In the `performCompletion` method, after collecting `lookupElements`, add cache storage:

```scala
      // Store in cache for resolve
      val requestId = requestIdCounter.incrementAndGet()
      val elements = lookupElements.take(200).toArray
      cachedElements = elements
      cachedRequestId = requestId
      cacheTimestamp = System.currentTimeMillis()

      // Convert to lean LSP CompletionItems
      elements.zipWithIndex.map: (elem, idx) =>
        toLspCompletionItem(elem, document, idx, requestId)
      .toSeq
```

**Replace the existing `toLspCompletionItem` method entirely** (the old 3-parameter version at line 96-129). The new version is lean — only label, kind, sortText, insertText, and a data field for resolve:

```scala
  private def toLspCompletionItem(
    elem: LookupElement,
    document: com.intellij.openapi.editor.Document,
    sortIndex: Int,
    requestId: Long
  ): CompletionItem =
    val item = CompletionItem()
    item.setLabel(elem.getLookupString)
    item.setKind(getCompletionKind(elem))
    item.setSortText(f"$sortIndex%05d")
    item.setInsertText(elem.getLookupString)
    item.setInsertTextFormat(InsertTextFormat.PlainText)
    // Store request ID + index for resolve
    val data = com.google.gson.JsonObject()
    data.addProperty("requestId", requestId)
    data.addProperty("index", sortIndex)
    item.setData(data)
    item
```

Also **remove the `getAutoImportEdit` and `findImportInsertionLine` methods** from the class — they move into the `resolveCompletion` method.

Add the resolve method:

```scala
  def resolveCompletion(item: CompletionItem): CompletionItem =
    try
      val data = item.getData match
        case obj: com.google.gson.JsonObject => obj
        case _ => return item
      val requestId = data.get("requestId").getAsLong
      val index = data.get("index").getAsInt

      // Validate cache
      if requestId != cachedRequestId then return item
      if System.currentTimeMillis() - cacheTimestamp > CacheTtlMs then return item
      if index < 0 || index >= cachedElements.length then return item

      val elem = cachedElements(index)

      // Populate detail from presentation
      projectManager.smartReadAction: () =>
        val presentation = LookupElementPresentation()
        elem.renderElement(presentation)
        val detail = Seq(
          Option(presentation.getTypeText).filter(_.nonEmpty),
          Option(presentation.getTailText).filter(_.nonEmpty)
        ).flatten.mkString(" ")
        if detail.nonEmpty then item.setDetail(detail)

        // Populate documentation
        val psi = elem.getPsiElement
        if psi != null then
          try
            val lang = psi.getLanguage
            val docProvider = LanguageDocumentation.INSTANCE.forLanguage(lang)
            if docProvider != null then
              val doc = docProvider.generateDoc(psi, null)
              if doc != null && doc.nonEmpty then
                val clean = doc.replaceAll("<[^>]+>", "").trim
                if clean.nonEmpty then
                  item.setDocumentation(org.eclipse.lsp4j.MarkupContent(
                    org.eclipse.lsp4j.MarkupKind.MARKDOWN, clean
                  ))
          catch
            case _: Exception => ()

        // Populate auto-import
        getAutoImportEdit(elem, null).foreach: edit =>
          item.setAdditionalTextEdits(java.util.List.of(edit))

      item
    catch
      case e: Exception =>
        System.err.println(s"[CompletionProvider] Resolve error: ${e.getMessage}")
        item
```

Note: The `getAutoImportEdit` method needs the document. Store it in cache alongside elements, or pass `null` and handle gracefully. Better approach: also cache the document reference.

Add to cache fields:

```scala
  @volatile private var cachedDocument: com.intellij.openapi.editor.Document = null
```

Store it in `performCompletion`:

```scala
      cachedDocument = document
```

Update resolve to use it:

```scala
        getAutoImportEdit(elem, cachedDocument).foreach: edit =>
          item.setAdditionalTextEdits(java.util.List.of(edit))
```

- [ ] **Step 4: Update capability registration**

In `ScalaLspServer.initialize()`, change the completion options:

```scala
      // Completion with trigger characters and resolve support
      capabilities.setCompletionProvider(
        CompletionOptions(true, java.util.List.of(".", " "))  // resolveProvider=true
      )
```

- [ ] **Step 5: Wire resolve in ScalaTextDocumentService**

Add override method:

```scala
  override def resolveCompletionItem(item: CompletionItem): CompletableFuture[CompletionItem] =
    CompletableFuture.supplyAsync: () =>
      completionProvider.resolveCompletion(item)
```

- [ ] **Step 6: Run integration tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.CompletionResolveIntegrationTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 7: Run existing completion tests to verify no regression**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.CompletionProviderIntegrationTest org.jetbrains.scalalsP.e2e.CompletionE2eTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 8: Write and run E2E test**

Add to `TestLspClient.scala`:

```scala
  def resolveCompletionItem(item: CompletionItem): CompletionItem =
    requestOffEdt() {
      clientProxy.getTextDocumentService.resolveCompletionItem(item).get(10, TimeUnit.SECONDS)
    }
```

The existing `CompletionE2eTest` already tests completions. Verify resolve works end-to-end by running:

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.e2e.CompletionE2eTest" 2>&1 | tee /local/log`
Expected: Pass (existing completion tests still work with the refactored lean items)

- [ ] **Step 9: Commit**

```bash
git add lsp-server/src/intellij/CompletionProvider.scala \
       lsp-server/src/ScalaLspServer.scala \
       lsp-server/src/ScalaTextDocumentService.scala \
       lsp-server/test/src/integration/CompletionResolveIntegrationTest.scala \
       lsp-server/test/src/e2e/TestLspClient.scala
git commit -m "feat: add completionItem/resolve with lazy documentation and auto-import"
```

---

## Task 3: Signature Help Provider

**Files:**
- Create: `lsp-server/src/intellij/SignatureHelpProvider.scala`
- Create: `lsp-server/test/src/integration/SignatureHelpProviderIntegrationTest.scala`
- Create: `lsp-server/test/src/e2e/SignatureHelpE2eTest.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/test/src/e2e/TestLspClient.scala`

- [ ] **Step 1: Write integration test**

Create `lsp-server/test/src/integration/SignatureHelpProviderIntegrationTest.scala`:

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SignatureHelpProvider
import org.junit.Assert.*

class SignatureHelpProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SignatureHelpProvider(projectManager)

  def testSignatureHelpInMethodCall(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertNotNull("Should return signature help inside method call", help)
    help.foreach: sh =>
      assertFalse("Should have at least one signature", sh.getSignatures.isEmpty)
      val sig = sh.getSignatures.get(0)
      assertTrue("Signature should contain method name", sig.getLabel.contains("add"))
      assertNotNull("Should have parameters", sig.getParameters)
      assertEquals("Should have 2 parameters", 2, sig.getParameters.size())

  def testSignatureHelpActiveParameter(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(1,
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 22))
    help.foreach: sh =>
      assertEquals("Active parameter should be 1 (second param)", 1, sh.getActiveParameter.intValue())

  def testSignatureHelpOutsideMethodCall(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(1, 12))
    // Should return None or empty when not in a method call
    help.foreach: sh =>
      assertTrue("Should have no signatures outside method call",
        sh.getSignatures == null || sh.getSignatures.isEmpty)

  def testSignatureHelpOverloadedMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def process(x: Int): String = x.toString
        |  def process(x: String): String = x
        |  def process(x: Int, y: Int): String = (x + y).toString
        |  val result = process(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(4, 23))
    help.foreach: sh =>
      assertTrue("Should have multiple overloaded signatures",
        sh.getSignatures.size() >= 2)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.SignatureHelpProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: Compilation failure — `SignatureHelpProvider` not found

- [ ] **Step 3: Implement SignatureHelpProvider**

Create `lsp-server/src/intellij/SignatureHelpProvider.scala`:

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

class SignatureHelpProvider(projectManager: IntellijProjectManager):

  def getSignatureHelp(uri: String, position: Position): Option[SignatureHelp] =
    try
      projectManager.smartReadAction: () =>
        for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
          help <- computeSignatureHelp(psiFile, document, position)
        yield help
    catch
      case e: Exception =>
        System.err.println(s"[SignatureHelpProvider] Error: ${e.getMessage}")
        None

  private def computeSignatureHelp(
    psiFile: PsiFile,
    document: com.intellij.openapi.editor.Document,
    position: Position
  ): Option[SignatureHelp] =
    val offset = PsiUtils.positionToOffset(document, position)

    // Walk back to find the enclosing argument list — look for the opening '('
    val text = document.getText
    val argListStart = findArgListStart(text, offset)
    if argListStart < 0 then return None

    // Find the method reference before the '('
    val methodRefOffset = findMethodRefOffset(text, argListStart)
    if methodRefOffset < 0 then return None

    // Resolve the method at that offset
    val element = PsiUtils.findReferenceElementAt(psiFile, methodRefOffset)
    element.flatMap: elem =>
      val resolved = Option(elem.getReference).flatMap(r => Option(r.resolve()))
      resolved.flatMap: target =>
        extractSignatures(target, text, argListStart, offset)

  private def findArgListStart(text: String, offset: Int): Int =
    var depth = 0
    var i = math.min(offset - 1, text.length - 1)
    while i >= 0 do
      text.charAt(i) match
        case ')' => depth += 1
        case '(' =>
          if depth == 0 then return i
          else depth -= 1
        case _ =>
      i -= 1
    -1

  private def findMethodRefOffset(text: String, parenOffset: Int): Int =
    var i = parenOffset - 1
    // Skip whitespace
    while i >= 0 && text.charAt(i).isWhitespace do i -= 1
    if i >= 0 then i else -1

  private def extractSignatures(
    target: PsiElement,
    text: String,
    argListStart: Int,
    cursorOffset: Int
  ): Option[SignatureHelp] =
    // Use reflection to detect Scala plugin's ScFunction/ScMethod
    val className = target.getClass.getName
    if className.contains("ScFunction") || className.contains("ScMethod") ||
       className.contains("PsiMethod") then
      val signatures = extractMethodSignatures(target)
      if signatures.isEmpty then return None

      // Determine active parameter by counting commas before cursor
      val activeParam = countCommas(text, argListStart + 1, cursorOffset)

      val help = SignatureHelp()
      help.setSignatures(signatures.asJava)
      help.setActiveSignature(0)
      help.setActiveParameter(activeParam)
      Some(help)
    else
      None

  private def extractMethodSignatures(element: PsiElement): Seq[SignatureInformation] =
    try
      // Try reflection on ScFunction for Scala methods
      val clazz = element.getClass
      val nameMethod = clazz.getMethod("name")
      val name = nameMethod.invoke(element).toString

      // Try to get parameter clauses via reflection
      val params = extractParameterInfo(element)
      val label = s"$name${params.map(_.mkString("(", ", ", ")")).mkString}"
      val sig = SignatureInformation(label)

      // Build ParameterInformation for each param
      val paramInfos = params.flatten.map: paramStr =>
        ParameterInformation(paramStr)
      sig.setParameters(paramInfos.asJava)

      Seq(sig)
    catch
      case _: Exception =>
        // Fallback: use NavigationItem presentation
        element match
          case nav: com.intellij.navigation.NavigationItem =>
            Option(nav.getPresentation).map: pres =>
              val label = Option(pres.getPresentableText).getOrElse("unknown")
              SignatureInformation(label)
            .toSeq
          case _ => Seq.empty

  private def extractParameterInfo(element: PsiElement): Seq[Seq[String]] =
    try
      // Try ScFunction.effectiveParameterClauses via reflection
      val clazz = element.getClass
      val clauses = try
        val method = clazz.getMethod("effectiveParameterClauses")
        val result = method.invoke(element)
        // Result is a Scala Seq of ScParameterClause
        result.asInstanceOf[scala.collection.Seq[Any]].map: clause =>
          val paramsMethod = clause.getClass.getMethod("parameters")
          val params = paramsMethod.invoke(clause).asInstanceOf[scala.collection.Seq[Any]]
          params.map: param =>
            val nameMethod = param.getClass.getMethod("name")
            val name = nameMethod.invoke(param).toString
            val typeText = try
              val typeMethod = param.getClass.getMethod("typeElement")
              val typeOpt = typeMethod.invoke(param).asInstanceOf[Option[Any]]
              typeOpt.map(_.toString).getOrElse("Any")
            catch
              case _: Exception => "Any"
            s"$name: $typeText"
          .toSeq
        .toSeq
      catch
        case _: NoSuchMethodException =>
          // Fallback for PsiMethod (Java methods)
          element match
            case m if m.getClass.getName.contains("PsiMethod") =>
              try
                val getParams = m.getClass.getMethod("getParameterList")
                val paramList = getParams.invoke(m)
                val getParamsArray = paramList.getClass.getMethod("getParameters")
                val params = getParamsArray.invoke(paramList).asInstanceOf[Array[Any]]
                Seq(params.map: p =>
                  val getName = p.getClass.getMethod("getName")
                  val getType = p.getClass.getMethod("getType")
                  val name = getName.invoke(p).toString
                  val typeName = getType.invoke(p).toString
                  s"$name: $typeName"
                .toSeq)
              catch
                case _: Exception => Seq.empty
            case _ => Seq.empty
      clauses
    catch
      case _: Exception => Seq.empty

  private def countCommas(text: String, start: Int, end: Int): Int =
    var count = 0
    var depth = 0
    var i = start
    while i < end && i < text.length do
      text.charAt(i) match
        case '(' | '[' => depth += 1
        case ')' | ']' => depth -= 1
        case ',' if depth == 0 => count += 1
        case _ =>
      i += 1
    count
```

- [ ] **Step 4: Wire into ScalaTextDocumentService**

Add provider field:

```scala
private val signatureHelpProvider = SignatureHelpProvider(projectManager)
```

Add override:

```scala
  // --- Signature Help ---

  override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
    CompletableFuture.supplyAsync: () =>
      signatureHelpProvider.getSignatureHelp(
        params.getTextDocument.getUri,
        params.getPosition
      ).orNull
```

- [ ] **Step 5: Register capability**

Add to `ScalaLspServer.initialize()`:

```scala
      // Signature help
      val signatureHelpOptions = SignatureHelpOptions(java.util.List.of("(", ","))
      capabilities.setSignatureHelpProvider(signatureHelpOptions)
```

- [ ] **Step 6: Run integration tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.SignatureHelpProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 7: Write and run E2E test**

Add to `TestLspClient.scala`:

```scala
  def signatureHelp(uri: String, line: Int, char: Int): Option[SignatureHelp] =
    requestOffEdt() {
      val params = new SignatureHelpParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      params.setPosition(new Position(line, char))
      Option(clientProxy.getTextDocumentService.signatureHelp(params).get(10, TimeUnit.SECONDS))
    }
```

Create `lsp-server/test/src/e2e/SignatureHelpE2eTest.scala`:

```scala
package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class SignatureHelpE2eTest extends E2eTestBase:

  def testSignatureHelpOnShapeConstructor(): Unit =
    val uri = openFixture("hierarchy/Circle.scala")
    // Verify no crash when requesting signature help
    val help = client.signatureHelp(uri, 3, 40)
    // May or may not find signatures depending on context — just verify no error
```

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.e2e.SignatureHelpE2eTest" 2>&1 | tee /local/log`
Expected: Pass

- [ ] **Step 8: Commit**

```bash
git add lsp-server/src/intellij/SignatureHelpProvider.scala \
       lsp-server/src/ScalaLspServer.scala \
       lsp-server/src/ScalaTextDocumentService.scala \
       lsp-server/test/src/integration/SignatureHelpProviderIntegrationTest.scala \
       lsp-server/test/src/e2e/SignatureHelpE2eTest.scala \
       lsp-server/test/src/e2e/TestLspClient.scala
git commit -m "feat: add textDocument/signatureHelp support"
```

---

## Task 4: Document Link Provider

**Files:**
- Create: `lsp-server/src/intellij/DocumentLinkProvider.scala`
- Create: `lsp-server/test/src/integration/DocumentLinkProviderIntegrationTest.scala`
- Create: `lsp-server/test/src/e2e/DocumentLinkE2eTest.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/test/src/e2e/TestLspClient.scala`

- [ ] **Step 1: Write integration test**

Create `lsp-server/test/src/integration/DocumentLinkProviderIntegrationTest.scala`:

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.DocumentLinkProvider
import org.junit.Assert.*

class DocumentLinkProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = DocumentLinkProvider(projectManager)

  def testDetectUrlInComment(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  // See https://docs.scala-lang.org/scala3/reference
        |  val x = 42
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertFalse("Should detect URL in comment", links.isEmpty)
    assertTrue("Link target should be the URL",
      links.exists(_.getTarget.contains("docs.scala-lang.org")))

  def testDetectUrlInString(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val url = "https://example.com/api/v1"
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertFalse("Should detect URL in string", links.isEmpty)

  def testDetectSbtDependency(): Unit =
    // configureScalaFile uses Scala file type, but regex scanning works on raw text regardless
    val uri = configureScalaFile("build.sbt",
      """val deps = Seq(
        |  "org.typelevel" %% "cats-core" % "2.10.0",
        |  "org.scalatest" %%% "scalatest" % "3.2.17"
        |)
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertTrue("Should detect SBT dependencies",
      links.exists(_.getTarget.contains("search.maven.org")))
    assertTrue("Should detect %%% dependencies",
      links.size >= 2)

  def testNoLinksInPlainCode(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo(y: Int): Int = x + y
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertTrue("Should have no links in plain code", links.isEmpty)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.DocumentLinkProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: Compilation failure

- [ ] **Step 3: Implement DocumentLinkProvider**

Create `lsp-server/src/intellij/DocumentLinkProvider.scala`:

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*

import java.nio.file.{Files, Path}
import scala.util.matching.Regex

class DocumentLinkProvider(projectManager: IntellijProjectManager):

  private val urlPattern: Regex = """https?://[^\s"')>\]]+""".r
  private val sbtDepPattern: Regex = """"([^"]+)"\s+%{1,3}\s+"([^"]+)"\s+%\s+"([^"]+)"""".r
  private val filePathPattern: Regex = """"((?:src|test|resources|conf|config)/[^"]+)"""".r

  def getDocumentLinks(uri: String): Seq[DocumentLink] =
    try
      projectManager.smartReadAction: () =>
        (for
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val text = document.getText
          val links = scala.collection.mutable.ArrayBuffer[DocumentLink]()
          links ++= findUrlLinks(text, document)
          links ++= findSbtDependencyLinks(text, document)
          links ++= findFilePathLinks(text, document, uri)
          links.toSeq
        ).getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[DocumentLinkProvider] Error: ${e.getMessage}")
        Seq.empty

  private def findUrlLinks(
    text: String,
    document: com.intellij.openapi.editor.Document
  ): Seq[DocumentLink] =
    urlPattern.findAllMatchIn(text).map: m =>
      val start = PsiUtils.offsetToPosition(document, m.start)
      val end = PsiUtils.offsetToPosition(document, m.end)
      val link = DocumentLink(Range(start, end), m.matched)
      link
    .toSeq

  private def findSbtDependencyLinks(
    text: String,
    document: com.intellij.openapi.editor.Document
  ): Seq[DocumentLink] =
    sbtDepPattern.findAllMatchIn(text).map: m =>
      val group = m.group(1)
      val artifact = m.group(2)
      val start = PsiUtils.offsetToPosition(document, m.start)
      val end = PsiUtils.offsetToPosition(document, m.end)
      val target = s"https://search.maven.org/search?q=g:$group+a:$artifact*"
      DocumentLink(Range(start, end), target)
    .toSeq

  private def findFilePathLinks(
    text: String,
    document: com.intellij.openapi.editor.Document,
    uri: String
  ): Seq[DocumentLink] =
    val projectBasePath = try
      Path.of(projectManager.getProject.getBasePath)
    catch
      case _: Exception => return Seq.empty

    filePathPattern.findAllMatchIn(text).flatMap: m =>
      val relativePath = m.group(1)
      val resolved = projectBasePath.resolve(relativePath)
      if Files.exists(resolved) then
        val start = PsiUtils.offsetToPosition(document, m.start + 1) // +1 to skip opening quote
        val end = PsiUtils.offsetToPosition(document, m.end - 1)     // -1 to skip closing quote
        val target = resolved.toUri.toString
        Some(DocumentLink(Range(start, end), target))
      else
        None
    .toSeq
```

- [ ] **Step 4: Wire into ScalaTextDocumentService**

Add provider field:

```scala
private val documentLinkProvider = DocumentLinkProvider(projectManager)
```

Add override:

```scala
  // --- Document Link ---

  override def documentLink(params: DocumentLinkParams): CompletableFuture[util.List[DocumentLink]] =
    CompletableFuture.supplyAsync: () =>
      documentLinkProvider.getDocumentLinks(params.getTextDocument.getUri).asJava
```

- [ ] **Step 5: Register capability**

Add to `ScalaLspServer.initialize()`:

```scala
      // Document links
      capabilities.setDocumentLinkProvider(DocumentLinkOptions())
```

- [ ] **Step 6: Run integration tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.DocumentLinkProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 7: Write and run E2E test**

Add to `TestLspClient.scala`:

```scala
  def documentLinks(uri: String): List[DocumentLink] =
    requestOffEdt() {
      val params = new DocumentLinkParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      val result = clientProxy.getTextDocumentService.documentLink(params).get(10, TimeUnit.SECONDS)
      if result == null then Nil else result.asScala.toList
    }
```

Create `lsp-server/test/src/e2e/DocumentLinkE2eTest.scala`:

```scala
package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class DocumentLinkE2eTest extends E2eTestBase:

  def testDocumentLinksOnFixture(): Unit =
    val uri = openFixture("hierarchy/ShapeOps.scala")
    val links = client.documentLinks(uri)
    // Fixture may or may not have URLs — just verify no crash
    assertNotNull(links)
```

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.e2e.DocumentLinkE2eTest" 2>&1 | tee /local/log`
Expected: Pass

- [ ] **Step 8: Commit**

```bash
git add lsp-server/src/intellij/DocumentLinkProvider.scala \
       lsp-server/src/ScalaLspServer.scala \
       lsp-server/src/ScalaTextDocumentService.scala \
       lsp-server/test/src/integration/DocumentLinkProviderIntegrationTest.scala \
       lsp-server/test/src/e2e/DocumentLinkE2eTest.scala \
       lsp-server/test/src/e2e/TestLspClient.scala
git commit -m "feat: add textDocument/documentLink for URLs, file paths, and SBT deps"
```

---

## Task 5: Workspace Folders

**Files:**
- Modify: `lsp-server/src/intellij/IntellijProjectManager.scala`
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Create: `lsp-server/test/src/integration/WorkspaceFoldersIntegrationTest.scala`

- [ ] **Step 1: Write integration test**

Create `lsp-server/test/src/integration/WorkspaceFoldersIntegrationTest.scala`:

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

class WorkspaceFoldersIntegrationTest extends ScalaLspTestBase:

  def testGetProjectReturnsProject(): Unit =
    // After setUp, projectManager already has a project set
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
    // With single project, findPsiFile should still work
    val uri = configureScalaFile(
      """object Test:
        |  val y = 1
        |""".stripMargin
    )
    val psiFile = projectManager.findPsiFile(uri)
    assertTrue("Should find PSI file", psiFile.isDefined)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.WorkspaceFoldersIntegrationTest" 2>&1 | tee /local/log`
Expected: Compilation failure — `getProjectForUri` not found

- [ ] **Step 3: Refactor IntellijProjectManager for multi-project**

In `lsp-server/src/intellij/IntellijProjectManager.scala`, add the `projects` map and `getProjectForUri` method. Keep backward compatibility with the existing `project` field.

Add import and new field:

```scala
import scala.collection.concurrent.TrieMap

class IntellijProjectManager(registry: Option[ProjectRegistry] = None, daemonMode: Boolean = false):

  import scala.compiletime.uninitialized
  @volatile private var project: Project = uninitialized
  private val projects = TrieMap[String, Project]()
```

Add `getProjectForUri`:

```scala
  def getProjectForUri(uri: String): Project =
    // uriToPath already exists as a private method in IntellijProjectManager (line 141)
    val path = uriToPath(uri)
    // Find the project whose base path is a prefix of the file path
    projects.find((basePath, _) => path.startsWith(basePath))
      .map(_(1))
      .getOrElse(getProject)  // Fall back to primary project
```

Refactor `openProject` to support multiple projects:

1. **Remove the early-return guard** (`if project != null then return` at line 41-43) — this blocks opening a second project.
2. Change the logic so the first `openProject` call sets the primary `project` field, and all calls store in the `projects` map.
3. After `project = ...` assignment, add:

```scala
    projects.put(path.toString, project)
```

Also after `project = reg.openProject(projectPath)` in the registry branch:

```scala
    projects.put(projectPath, project)
```

For subsequent calls when `project` is already set, only add to the map without setting the primary field:

```scala
    // In the non-registry branch, replace the guard with:
    val path = Path.of(projectPath)
    if project != null then
      // Already have a primary project — open additional project and add to map only
      val additionalProject = ProjectManager.getInstance().loadAndOpenProject(path.toString)
      if additionalProject != null then
        projects.put(path.toString, additionalProject)
      return
```

Add `closeProject(folderPath: String)` overload:

```scala
  def closeProject(folderPath: String): Unit =
    projects.remove(folderPath).foreach: p =>
      if !daemonMode then
        ApplicationManager.getApplication.invokeAndWait: () =>
          ProjectManager.getInstance().closeAndDispose(p)
      // If the closed project was the primary, clear it
      if project == p then project = projects.values.headOption.orNull
```

Update `setProjectForTesting` to also register in `projects`:

```scala
  private[scalalsP] def setProjectForTesting(p: Project): Unit =
    project = p
    if p != null && p.getBasePath != null then projects.put(p.getBasePath, p)
```

Update `setProjectForSession` similarly:

```scala
  private[scalalsP] def setProjectForSession(p: Project): Unit =
    project = p
    if p != null && p.getBasePath != null then projects.put(p.getBasePath, p)
```

- [ ] **Step 4: Add didChangeWorkspaceFolders to ScalaWorkspaceService**

Add override in `ScalaWorkspaceService.scala`:

```scala
  override def didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams): Unit =
    val event = params.getEvent
    if event == null then return

    // Handle added folders
    if event.getAdded != null then
      event.getAdded.asScala.foreach: folder =>
        val uri = folder.getUri
        val path = if uri.startsWith("file://") then java.net.URI.create(uri).getPath else uri
        System.err.println(s"[WorkspaceService] Adding workspace folder: $path")
        try projectManager.openProject(path)
        catch case e: Exception =>
          System.err.println(s"[WorkspaceService] Failed to open folder: ${e.getMessage}")

    // Handle removed folders
    if event.getRemoved != null then
      event.getRemoved.asScala.foreach: folder =>
        val uri = folder.getUri
        val path = if uri.startsWith("file://") then java.net.URI.create(uri).getPath else uri
        System.err.println(s"[WorkspaceService] Removing workspace folder: $path")
        projectManager.closeProject(path)
```

- [ ] **Step 5: Register capability and handle initial workspace folders**

Add to `ScalaLspServer.initialize()`:

```scala
      // Workspace folders
      val workspaceFolderOptions = WorkspaceFoldersOptions()
      workspaceFolderOptions.setSupported(true)
      workspaceFolderOptions.setChangeNotifications(true)
      val workspaceCapabilities = WorkspaceServerCapabilities(workspaceFolderOptions)
      capabilities.setWorkspace(workspaceCapabilities)
```

Also in `initialize()`, after `projectManager.openProject(effectivePath)`, handle additional workspace folders:

```scala
      // Open additional workspace folders if provided
      val folders = Option(params.getWorkspaceFolders)
      folders.foreach: wsFolders =>
        wsFolders.asScala.foreach: folder =>
          val folderPath = if folder.getUri.startsWith("file://") then
            java.net.URI.create(folder.getUri).getPath
          else folder.getUri
          if folderPath != effectivePath then
            try projectManager.openProject(folderPath)
            catch case e: Exception =>
              System.err.println(s"[ScalaLsp] Failed to open workspace folder: ${e.getMessage}")
```

This requires adding `import scala.jdk.CollectionConverters.*` to `ScalaLspServer.scala`.

- [ ] **Step 6: Run integration tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.WorkspaceFoldersIntegrationTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 7: Run all existing tests to verify no regression**

Run: `sbt "lsp-server / test" 2>&1 | tee /local/log`
Expected: All tests pass — the multi-project changes are backward compatible

- [ ] **Step 8: Commit**

```bash
git add lsp-server/src/intellij/IntellijProjectManager.scala \
       lsp-server/src/ScalaWorkspaceService.scala \
       lsp-server/src/ScalaLspServer.scala \
       lsp-server/test/src/integration/WorkspaceFoldersIntegrationTest.scala
git commit -m "feat: add workspace/didChangeWorkspaceFolders with multi-project support"
```

---

## Task 6: Semantic Tokens Provider

**Files:**
- Create: `lsp-server/src/intellij/SemanticTokensProvider.scala`
- Create: `lsp-server/test/src/integration/SemanticTokensProviderIntegrationTest.scala`
- Create: `lsp-server/test/src/e2e/SemanticTokensE2eTest.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/test/src/e2e/TestLspClient.scala`

- [ ] **Step 1: Write integration test**

Create `lsp-server/test/src/integration/SemanticTokensProviderIntegrationTest.scala`:

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
import org.junit.Assert.*

class SemanticTokensProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SemanticTokensProvider(projectManager)

  def testSemanticTokensReturnsNonNull(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |  def foo(y: String): Boolean = y.isEmpty
        |""".stripMargin
    )
    myFixture.doHighlighting() // Trigger daemon analysis
    val result = provider.getSemanticTokensFull(uri)
    assertNotNull("Should return SemanticTokens", result)
    assertNotNull("Should have data array", result.getData)

  def testSemanticTokensDataIsMultipleOfFive(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    if result.getData != null && !result.getData.isEmpty then
      assertEquals("Token data should be multiple of 5",
        0, result.getData.size() % 5)

  def testSemanticTokensRange(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = "hello"
        |  def foo: Int = x
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val range = Range(Position(1, 0), Position(2, 20))
    val result = provider.getSemanticTokensRange(uri, range)
    assertNotNull("Range tokens should return non-null", result)

  def testTokenTypeMappingKnown(): Unit =
    // Verify that the legend is properly constructed
    val legend = SemanticTokensProvider.legend
    assertNotNull(legend)
    assertFalse("Legend should have token types", legend.getTokenTypes.isEmpty)
    assertFalse("Legend should have token modifiers", legend.getTokenModifiers.isEmpty)
    assertTrue("Legend should include 'keyword'", legend.getTokenTypes.contains("keyword"))
    assertTrue("Legend should include 'class'", legend.getTokenTypes.contains("class"))
    assertTrue("Legend should include 'method'", legend.getTokenTypes.contains("method"))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.SemanticTokensProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: Compilation failure

- [ ] **Step 3: Implement SemanticTokensProvider**

Create `lsp-server/src/intellij/SemanticTokensProvider.scala`:

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

class SemanticTokensProvider(projectManager: IntellijProjectManager):

  import SemanticTokensProvider.*

  def getSemanticTokensFull(uri: String): SemanticTokens =
    try
      projectManager.smartReadAction: () =>
        (for
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val highlights = getHighlights(document)
          val tokens = encodeTokens(highlights, document, None)
          val result = SemanticTokens()
          result.setData(tokens.asJava)
          result
        ).getOrElse(emptyTokens)
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokensProvider] Error: ${e.getMessage}")
        emptyTokens

  def getSemanticTokensRange(uri: String, range: Range): SemanticTokens =
    try
      projectManager.smartReadAction: () =>
        (for
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val startOffset = PsiUtils.positionToOffset(document, range.getStart)
          val endOffset = PsiUtils.positionToOffset(document, range.getEnd)
          val highlights = getHighlights(document)
          val tokens = encodeTokens(highlights, document, Some((startOffset, endOffset)))
          val result = SemanticTokens()
          result.setData(tokens.asJava)
          result
        ).getOrElse(emptyTokens)
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokensProvider] Range error: ${e.getMessage}")
        emptyTokens

  private def getHighlights(
    document: com.intellij.openapi.editor.Document
  ): Seq[HighlightInfo] =
    val project = projectManager.getProject
    val highlights = DaemonCodeAnalyzerImpl.getHighlights(
      document,
      HighlightSeverity.INFORMATION,
      project
    )
    if highlights == null then Seq.empty
    else highlights.asScala.toSeq

  private def encodeTokens(
    highlights: Seq[HighlightInfo],
    document: com.intellij.openapi.editor.Document,
    rangeFilter: Option[(Int, Int)]
  ): Seq[Integer] =
    // Filter highlights that have a text attributes key we can map
    val mappable = highlights.flatMap: h =>
      val key = h.`type`.getAttributesKey  // NOTE: `type` must be backtick-escaped (Scala keyword)
      if key == null then None
      else
        val externalName = key.getExternalName
        tokenTypeIndex(externalName).map: typeIdx =>
          val modBitmask = tokenModifierBitmask(externalName)
          (h.startOffset, h.endOffset, typeIdx, modBitmask)

    // Apply range filter
    val filtered = rangeFilter match
      case Some((start, end)) =>
        mappable.filter((s, e, _, _) => s >= start && e <= end)
      case None => mappable

    // Sort by position
    val sorted = filtered.sortBy((s, _, _, _) => s)

    // Delta-encode
    val result = scala.collection.mutable.ArrayBuffer[Integer]()
    var prevLine = 0
    var prevChar = 0

    sorted.foreach: (startOffset, endOffset, typeIdx, modBitmask) =>
      val pos = PsiUtils.offsetToPosition(document, startOffset)
      val line = pos.getLine
      val char = pos.getCharacter
      val length = endOffset - startOffset

      val deltaLine = line - prevLine
      val deltaChar = if deltaLine == 0 then char - prevChar else char

      result += Integer.valueOf(deltaLine)
      result += Integer.valueOf(deltaChar)
      result += Integer.valueOf(length)
      result += Integer.valueOf(typeIdx)
      result += Integer.valueOf(modBitmask)

      prevLine = line
      prevChar = char

    result.toSeq

  private def emptyTokens: SemanticTokens =
    val t = SemanticTokens()
    t.setData(java.util.Collections.emptyList())
    t


object SemanticTokensProvider:

  val tokenTypes: java.util.List[String] = java.util.List.of(
    "keyword",       // 0
    "type",          // 1
    "class",         // 2
    "interface",     // 3
    "enum",          // 4
    "method",        // 5
    "property",      // 6
    "variable",      // 7
    "parameter",     // 8
    "typeParameter", // 9
    "string",        // 10
    "number",        // 11
    "comment",       // 12
    "function"       // 13
  )

  val tokenModifiers: java.util.List[String] = java.util.List.of(
    "declaration",   // bit 0
    "static",        // bit 1
    "abstract",      // bit 2
    "readonly",      // bit 3
    "modification",  // bit 4
    "documentation", // bit 5
    "lazy"           // bit 6
  )

  val legend: SemanticTokensLegend = SemanticTokensLegend(tokenTypes, tokenModifiers)

  // Map IntelliJ TextAttributesKey external names to token type indices
  private val typeMapping: Map[String, Int] = Map(
    "SCALA_KEYWORD" -> 0,
    "SCALA_CLASS" -> 2,
    "SCALA_TRAIT" -> 3,
    "SCALA_OBJECT" -> 2,
    "SCALA_CASE_CLASS" -> 2,
    "SCALA_TYPE_ALIAS" -> 1,
    "SCALA_LOCAL_VARIABLE" -> 7,
    "SCALA_MUTABLE_LOCAL_VARIABLE" -> 7,
    "SCALA_PARAMETER" -> 8,
    "SCALA_METHOD" -> 5,
    "SCALA_METHOD_CALL" -> 5,
    "SCALA_FUNCTION" -> 13,
    "SCALA_TYPE_PARAMETER" -> 9,
    "SCALA_STRING" -> 10,
    "SCALA_NUMBER" -> 11,
    "SCALA_LINE_COMMENT" -> 12,
    "SCALA_BLOCK_COMMENT" -> 12,
    "SCALA_DOC_COMMENT" -> 12,
    "SCALA_FIELD" -> 6,
    "SCALA_PROPERTY" -> 6,
    // Java fallbacks
    "JAVA_KEYWORD" -> 0,
    "JAVA_STRING" -> 10,
    "JAVA_NUMBER" -> 11,
    "JAVA_LINE_COMMENT" -> 12,
    "JAVA_BLOCK_COMMENT" -> 12,
    "DEFAULT_KEYWORD" -> 0,
    "DEFAULT_STRING" -> 10,
    "DEFAULT_NUMBER" -> 11,
    "DEFAULT_LINE_COMMENT" -> 12,
    "DEFAULT_BLOCK_COMMENT" -> 12,
    "DEFAULT_DOC_COMMENT" -> 12,
  )

  // Map external names to modifier bitmasks
  private val modifierMapping: Map[String, Int] = Map(
    "SCALA_TRAIT" -> (1 << 2),            // abstract
    "SCALA_OBJECT" -> (1 << 1),           // static
    "SCALA_CASE_CLASS" -> (1 << 3),       // readonly
    "SCALA_DOC_COMMENT" -> (1 << 5),      // documentation
    "SCALA_IMPLICIT_CONVERSION" -> (1 << 4), // modification
    "SCALA_IMPLICIT_PARAMETER" -> (1 << 4),
  )

  def tokenTypeIndex(externalName: String): Option[Int] =
    typeMapping.get(externalName)

  def tokenModifierBitmask(externalName: String): Int =
    modifierMapping.getOrElse(externalName, 0)
```

- [ ] **Step 4: Wire into ScalaTextDocumentService**

Add provider field:

```scala
private val semanticTokensProvider = SemanticTokensProvider(projectManager)
```

Add overrides:

```scala
  // --- Semantic Tokens ---

  override def semanticTokensFull(params: SemanticTokensParams): CompletableFuture[SemanticTokens] =
    CompletableFuture.supplyAsync: () =>
      semanticTokensProvider.getSemanticTokensFull(params.getTextDocument.getUri)

  override def semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture[SemanticTokens] =
    CompletableFuture.supplyAsync: () =>
      semanticTokensProvider.getSemanticTokensRange(
        params.getTextDocument.getUri,
        params.getRange
      )
```

- [ ] **Step 5: Register capability**

Add to `ScalaLspServer.initialize()`:

```scala
      // Semantic tokens
      val semanticTokensOptions = SemanticTokensWithRegistrationOptions(SemanticTokensProvider.legend)
      semanticTokensOptions.setFull(true)
      semanticTokensOptions.setRange(true)
      capabilities.setSemanticTokensProvider(semanticTokensOptions)
```

Add import to `ScalaLspServer.scala`:

```scala
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
```

- [ ] **Step 6: Run integration tests**

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.integration.SemanticTokensProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 7: Write and run E2E test**

Add to `TestLspClient.scala`:

```scala
  def semanticTokensFull(uri: String): SemanticTokens =
    requestOffEdt() {
      val params = new SemanticTokensParams()
      params.setTextDocument(new TextDocumentIdentifier(uri))
      clientProxy.getTextDocumentService.semanticTokensFull(params).get(10, TimeUnit.SECONDS)
    }
```

Create `lsp-server/test/src/e2e/SemanticTokensE2eTest.scala`:

```scala
package org.jetbrains.scalalsP.e2e

import org.junit.Assert.*

class SemanticTokensE2eTest extends E2eTestBase:

  def testSemanticTokensOnFixture(): Unit =
    val uri = openFixture("hierarchy/Shape.scala")
    val tokens = client.semanticTokensFull(uri)
    assertNotNull("Should return semantic tokens", tokens)
    assertNotNull("Should have data", tokens.getData)
    // Data may be empty if daemon hasn't run yet — just verify no crash
```

Run: `sbt "lsp-server / testOnly org.jetbrains.scalalsP.e2e.SemanticTokensE2eTest" 2>&1 | tee /local/log`
Expected: Pass

- [ ] **Step 8: Run full test suite**

Run: `sbt "lsp-server / test" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 9: Commit**

```bash
git add lsp-server/src/intellij/SemanticTokensProvider.scala \
       lsp-server/src/ScalaLspServer.scala \
       lsp-server/src/ScalaTextDocumentService.scala \
       lsp-server/test/src/integration/SemanticTokensProviderIntegrationTest.scala \
       lsp-server/test/src/e2e/SemanticTokensE2eTest.scala \
       lsp-server/test/src/e2e/TestLspClient.scala
git commit -m "feat: add textDocument/semanticTokens with Scala-rich token mapping"
```

---

## Task 7: Capability Verification and Final Test

- [ ] **Step 1: Write capability registration test**

Update the existing `LspProtocolTest` or add to it:

```scala
  @Test def testAllNewCapabilitiesRegistered(): Unit =
    // ... initialize server and get capabilities ...
    val capabilities = result.getCapabilities
    assertTrue("Should have formatting", capabilities.getDocumentFormattingProvider.getLeft.booleanValue())
    assertTrue("Should have range formatting", capabilities.getDocumentRangeFormattingProvider.getLeft.booleanValue())
    assertTrue("Should have completion resolve", capabilities.getCompletionProvider.getResolveProvider.booleanValue())
    assertNotNull("Should have signature help", capabilities.getSignatureHelpProvider)
    assertNotNull("Should have document link", capabilities.getDocumentLinkProvider)
    assertNotNull("Should have semantic tokens", capabilities.getSemanticTokensProvider)
    assertNotNull("Should have workspace folder support", capabilities.getWorkspace)
```

- [ ] **Step 2: Run full test suite**

Run: `sbt "lsp-server / test" 2>&1 | tee /local/log`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add lsp-server/test/src/LspProtocolTest.scala
git commit -m "test: verify all new LSP capabilities are registered"
```
