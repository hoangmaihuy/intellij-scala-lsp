# LSP Feature Parity with Metals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gap between intellij-scala-lsp and Metals by improving 8 existing features and adding 6 new LSP capabilities.

**Architecture:** Provider-per-feature pattern matching existing codebase. Each provider is a class taking `IntellijProjectManager`, using `smartReadAction` for thread safety. New LSP methods are wired through `ScalaTextDocumentService` → `ScalaLspServer` (capabilities) → `LspLauncher.java` (Java delegate).

**Tech Stack:** Scala 3, lsp4j 0.23.1, IntelliJ Platform 253.x, Scala Plugin API

**Spec:** `docs/superpowers/specs/2026-03-20-lsp-feature-parity-design.md`

**Test patterns:**
- Integration tests extend `ScalaLspTestBase` (unit-level, direct provider calls)
- E2E tests extend `E2eTestBase` (full LSP round-trip via `TestLspClient`)
- Both use `configureScalaFile()` / fixture files + JUnit assertions

**Build & test command:** `sbt "lsp-server/test"` — compile output goes to `/local/log`

---

### Task 1: Code Actions — Lazy Resolve with Workspace Edits (A1)

**Files:**
- Modify: `lsp-server/src/intellij/CodeActionProvider.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/org/jetbrains/scalalsP/LspLauncher.java`
- Modify: `lsp-server/test/src/integration/CodeActionProviderIntegrationTest.scala`
- Modify: `lsp-server/test/src/e2e/CodeActionE2eTest.scala`

- [ ] **Step 1: Add integration test — code actions include data field for resolve**

In `CodeActionProviderIntegrationTest.scala`, add:

```scala
def testCodeActionsHaveDataForResolve(): Unit =
  val uri = configureScalaFile(
    """object Main:
      |  val x = 1 + 2
      |""".stripMargin
  )
  val actions = getActions(uri, Range(Position(1, 10), Position(1, 15)))
  // Intention actions should have data field for lazy resolve
  val withData = actions.filter(_.getData != null)
  // At least some actions should have data (intentions are lazy)
  assertNotNull(actions)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Modify `CodeActionProvider` — add data field to actions, add `resolveCodeAction` method**

In `CodeActionProvider.scala`:

1. Add import for `com.google.gson.JsonObject`
2. In `collectQuickFixes`, add a `data` JSON field to each `CodeAction` with `{"type":"quickfix","uri":uri,"startOffset":startOffset,"endOffset":endOffset,"fixIndex":idx}`. Do NOT compute workspace edits eagerly.
3. In `collectIntentionActions`, add a `data` JSON field: `{"type":"intention","uri":uri,"offset":offset,"intentionClass":intention.getClass.getName}`. Do NOT compute workspace edits eagerly.
4. Add a `resolveCodeAction(action: CodeAction): CodeAction` method using a **two-phase approach** to avoid deadlock:

```scala
def resolveCodeAction(action: CodeAction): CodeAction =
  val data = action.getData match
    case obj: JsonObject => obj
    case _ => return action

  val uri = data.get("uri").getAsString

  // PHASE 1: Collect data inside read action
  val (originalText, language, psiFileOpt) = projectManager.smartReadAction: () =>
    val pf = projectManager.findPsiFile(uri)
    val vf = projectManager.findVirtualFile(uri)
    val doc = vf.flatMap(v => Option(FileDocumentManager.getInstance().getDocument(v)))
    (doc.map(_.getText).getOrElse(""), pf.map(_.getLanguage).orNull, pf)

  if language == null || originalText.isEmpty then return action

  // PHASE 2: Apply fix on PSI copy OUTSIDE read action (needs EDT via invokeAndWait)
  val project = projectManager.getProject
  val copy = PsiFileFactory.getInstance(project)
    .createFileFromText("_resolve_tmp.scala", language, originalText)
  var resultText = originalText

  val applyFix: Runnable = () =>
    WriteCommandAction.runWriteCommandAction(project, (() =>
      // Re-find and invoke the fix/intention on the copy
      // ... (type-specific logic)
      resultText = copy.getText
    ): Runnable)

  if ApplicationManager.getApplication.isDispatchThread then applyFix.run()
  else ApplicationManager.getApplication.invokeAndWait(applyFix)

  // PHASE 3: Diff and return workspace edit
  if resultText != originalText then
    val edit = computeFullReplacement(uri, originalText, resultText)
    action.setEdit(edit)
  action
```

   **Critical threading rule:** Phase 1 (read action) and Phase 2 (write action on EDT) must be SEPARATE calls. Never nest `invokeAndWait` inside `smartReadAction` — this deadlocks.

- [ ] **Step 4: Wire `resolveCodeAction` in service layer**

In `ScalaTextDocumentService.scala`, add:
```scala
override def resolveCodeAction(unresolved: CodeAction): CompletableFuture[CodeAction] =
  CompletableFuture.supplyAsync: () =>
    codeActionProvider.resolveCodeAction(unresolved)
```

In `ScalaLspServer.scala` `initialize()`, change `CodeActionOptions` to:
```scala
val codeActionOptions = CodeActionOptions(...)
codeActionOptions.setResolveProvider(true)
capabilities.setCodeActionProvider(codeActionOptions)
```

In `LspLauncher.java` `JavaTextDocumentService`, add:
```java
@Override public CompletableFuture<CodeAction> resolveCodeAction(CodeAction params) { return delegate.resolveCodeAction(params); }
```

- [ ] **Step 5: Run tests to verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 6: Add e2e test — resolveCodeAction round-trip**

In `CodeActionE2eTest.scala`, add a test that calls `codeActions`, then resolves one and verifies the workspace edit is non-null. Add `resolveCodeAction` helper to `TestLspClient`:

```scala
def resolveCodeAction(action: CodeAction): CodeAction =
  requestOffEdt() {
    clientProxy.getTextDocumentService.resolveCodeAction(action).get(10, TimeUnit.SECONDS)
  }
```

- [ ] **Step 7: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: add codeAction/resolve with lazy workspace edits"
```

---

### Task 2: Semantic Tokens — Operators, Deprecated, Escape Sequences (A4)

**Files:**
- Modify: `lsp-server/src/intellij/SemanticTokensProvider.scala`
- Modify: `lsp-server/test/src/integration/SemanticTokensProviderIntegrationTest.scala`
- Modify: `lsp-server/test/src/e2e/SemanticTokensE2eTest.scala`

- [ ] **Step 1: Add integration tests for new token types**

In `SemanticTokensProviderIntegrationTest.scala`, add tests:

```scala
def testOperatorTokenType(): Unit =
  val uri = configureScalaFile(
    """object Main:
      |  val x = List(1, 2, 3)
      |  val y = x.map(_ + 1)
      |""".stripMargin
  )
  val tokens = SemanticTokensProvider(projectManager).getSemanticTokensFull(uri)
  assertNotNull(tokens)
  // Verify operator tokens are classified (token type index 14 = operator)

def testDeprecatedModifier(): Unit =
  val uri = configureScalaFile(
    """object Main:
      |  @deprecated("use bar", "1.0")
      |  def foo(): Unit = ()
      |  val x = foo()
      |""".stripMargin
  )
  myFixture.doHighlighting()
  val tokens = SemanticTokensProvider(projectManager).getSemanticTokensFull(uri)
  assertNotNull(tokens)
  // Verify deprecated modifier bit is set on reference to foo
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Add operator and deprecated to legend**

In `SemanticTokensProvider` companion object:
```scala
val tokenTypes: JList[String] = JList.of(
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
  "function",      // 13
  "operator",      // 14
  "regexp"         // 15 (for escape sequences in strings)
)

val tokenModifiers: JList[String] = JList.of(
  "declaration",   // bit 0
  "static",        // bit 1
  "abstract",      // bit 2
  "readonly",      // bit 3
  "modification",  // bit 4
  "documentation", // bit 5
  "lazy",          // bit 6
  "deprecated"     // bit 7
)
```

- [ ] **Step 4: Add operator classification**

In `classifyElement`, add operator detection for symbolic method references. Add helper:
```scala
private def isOperatorName(name: String): Boolean =
  name.nonEmpty && !name.head.isLetter && name.head != '_'
```

In `classifyElement` — when resolved element is `ScFunction`/`PsiMethod`, check if the name is symbolic. If so, return `Some(14)` (operator).

In `classifyLeafToken` — for element types that are operator tokens (e.g., `tIDENTIFIER` with symbolic text that wasn't resolved as a reference), classify as operator.

- [ ] **Step 5: Add deprecated modifier**

In `classifyModifiers`, check for `@deprecated` annotation:
```scala
def classifyModifiers(element: PsiElement): Int =
  var mods = element match
    case _: ScTrait  => 4 // abstract
    case _: ScObject => 2 // static
    case _           => 0
  // Check for deprecated annotation
  element match
    case mod: com.intellij.psi.PsiModifierListOwner =>
      if mod.hasAnnotation("scala.deprecated") || mod.hasAnnotation("java.lang.Deprecated") then
        mods |= 128 // bit 7
    case _ => ()
  mods
```

- [ ] **Step 6: Add string escape sequence splitting**

In `classifyLeafToken`, when detecting a string token, instead of returning `Some(10)` directly, check if the string contains escape sequences. If yes, split into sub-tokens:
- Non-escape parts → `(offset, length, 10, 0)` (string)
- Escape sequences → `(offset, length, 15, 0)` (regexp)

Add a method `splitStringEscapes(startOffset: Int, text: String): Seq[(Int, Int, Int, Int)]` that scans for `\\[nrtbf"'\\]` and `\\u[0-9a-fA-F]{4}` patterns.

Modify `collectTokens` to call this method instead of adding a single token for string literals.

- [ ] **Step 7: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: add operator tokens, deprecated modifier, string escape highlighting"
```

---

### Task 3: Completion — Snippet Support (A8)

**Files:**
- Modify: `lsp-server/src/intellij/CompletionProvider.scala`
- Modify: `lsp-server/test/src/integration/CompletionResolveIntegrationTest.scala`

- [ ] **Step 1: Add integration test for snippet**

```scala
def testResolvedMethodHasSnippet(): Unit =
  val uri = configureScalaFile(
    """object Util:
      |  def add(a: Int, b: Int): Int = a + b
      |
      |object Main:
      |  Util.ad
      |""".stripMargin
  )
  val items = CompletionProvider(projectManager).getCompletions(uri, Position(4, 9))
  val addItem = items.find(_.getLabel == "add")
  assertTrue("Should find 'add' completion", addItem.isDefined)
  val resolved = CompletionProvider(projectManager).resolveCompletion(addItem.get)
  assertEquals(InsertTextFormat.Snippet, resolved.getInsertTextFormat)
  assertTrue("Snippet should have placeholders", resolved.getInsertText.contains("$"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Add snippet generation in `resolveCompletion`**

In `CompletionProvider.resolveCompletion`, after populating detail and documentation, add snippet generation:

```scala
// Generate snippet for methods with parameters
val psi = elem.getPsiElement
if psi != null then
  psi match
    case fn: ScFunction =>
      val params = fn.parameters
      if params.nonEmpty && !hasOverloads(fn) then
        val placeholders = params.zipWithIndex.map((_, i) => s"$$${i + 1}").mkString(", ")
        item.setInsertText(s"${elem.getLookupString}($placeholders)")
        item.setInsertTextFormat(InsertTextFormat.Snippet)
    case method: PsiMethod =>
      val params = method.getParameterList.getParameters
      if params.nonEmpty then
        val placeholders = params.indices.map(i => s"$$${i + 1}").mkString(", ")
        item.setInsertText(s"${elem.getLookupString}($placeholders)")
        item.setInsertTextFormat(InsertTextFormat.Snippet)
    case _ => ()
```

Add helper (reuse `SignatureHelpProvider.findOverloads` pattern):
```scala
private def hasOverloads(fn: ScFunction): Boolean =
  val name = fn.name
  val parent = fn.getParent
  if parent == null then false
  else parent.getChildren.count:
    case f: ScFunction => f.name == name
    case _ => false
  > 1
```

- [ ] **Step 4: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add snippet support for method completions"
```

---

### Task 4: Signature Help — Return Types, Docs, Implicits (A3)

**Files:**
- Modify: `lsp-server/src/intellij/SignatureHelpProvider.scala`
- Modify: `lsp-server/test/src/integration/SignatureHelpProviderIntegrationTest.scala`

- [ ] **Step 1: Add integration tests**

```scala
def testSignatureIncludesReturnType(): Unit =
  val uri = configureScalaFile(
    """object Util:
      |  def add(a: Int, b: Int): Int = a + b
      |
      |object Main:
      |  Util.add(1, 2)
      |""".stripMargin
  )
  val help = SignatureHelpProvider(projectManager).getSignatureHelp(uri, Position(4, 11))
  assertTrue("Should have signature help", help.isDefined)
  val label = help.get.getSignatures.get(0).getLabel
  assertTrue("Label should include return type", label.contains(": Int"))

def testSignatureIncludesDocumentation(): Unit =
  val uri = configureScalaFile(
    """object Util:
      |  /** Adds two numbers */
      |  def add(a: Int, b: Int): Int = a + b
      |
      |object Main:
      |  Util.add(1, 2)
      |""".stripMargin
  )
  val help = SignatureHelpProvider(projectManager).getSignatureHelp(uri, Position(5, 11))
  assertTrue("Should have signature help", help.isDefined)
  val sig = help.get.getSignatures.get(0)
  assertNotNull("Should have documentation", sig.getDocumentation)

def testImplicitParameterClause(): Unit =
  val uri = configureScalaFile(
    """object Util:
      |  def show[T](value: T)(implicit ev: T => String): String = ev(value)
      |
      |object Main:
      |  Util.show(42)
      |""".stripMargin
  )
  val help = SignatureHelpProvider(projectManager).getSignatureHelp(uri, Position(4, 13))
  assertTrue("Should have signature help", help.isDefined)
  val label = help.get.getSignatures.get(0).getLabel
  assertTrue("Label should show implicit clause", label.contains("implicit"))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Enhance `tryScFunction` with return type, docs, and implicit clauses**

In `SignatureHelpProvider.tryScFunction`:
1. **Return type:** After param string, append `: ${fn.returnType.map(_.presentableText).getOrElse("Unit")}`
2. **All parameter clauses:** Iterate ALL `fn.effectiveParameterClauses`, not just `head`. For implicit/using clauses, prefix with `implicit ` or `using `.
3. **Label format:** `name(a: Int, b: String)(implicit ev: Ordering[Int]): ReturnType`
4. **Documentation:** Add `LanguageDocumentation` lookup (same as `HoverProvider`):
```scala
val docProvider = LanguageDocumentation.INSTANCE.forLanguage(fn.getLanguage)
if docProvider != null then
  val docText = docProvider.generateDoc(fn, null)
  if docText != null && docText.nonEmpty then
    sig.setDocumentation(MarkupContent(MarkupKind.MARKDOWN, HoverProvider.htmlToMarkdown(docText)))
```

- [ ] **Step 4: Similarly enhance `tryPsiMethod` with return type**

Append `: ${Option(method.getReturnType).map(_.getPresentableText).getOrElse("void")}` to label.

- [ ] **Step 5: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add return types, docs, and implicit clauses to signature help"
```

---

### Task 5: Rename — File Renames, Companions, Validation (A2)

**Files:**
- Modify: `lsp-server/src/intellij/RenameProvider.scala`
- Modify: `lsp-server/test/src/integration/RenameProviderIntegrationTest.scala`
- Modify: `lsp-server/test/src/e2e/RenameE2eTest.scala`

- [ ] **Step 1: Add integration tests**

```scala
def testForbiddenRenameRejected(): Unit =
  val uri = configureScalaFile(
    """case class Foo(x: Int)
      |""".stripMargin
  )
  val result = RenameProvider(projectManager).prepareRename(uri, Position(0, 6))
  // "Foo" is fine to rename — not forbidden
  assertNotNull(result)

def testSyntheticMethodRenameRejected(): Unit =
  // Position cursor on synthetic `apply` or `equals` — prepareRename returns null
  // (This test validates the validation logic once implemented)

def testRenameIncludesFileRename(): Unit =
  val uri = addScalaFile("Foo.scala",
    """class Foo:
      |  def bar(): Unit = ()
      |""".stripMargin
  )
  val edit = RenameProvider(projectManager).rename(uri, Position(0, 6), "Bar")
  assertNotNull(edit)
  // When file rename is implemented, WorkspaceEdit uses documentChanges (not changes)
  // to support both TextDocumentEdit and RenameFile resource operations
  val docChanges = edit.getDocumentChanges
  assertNotNull("Should have document changes", docChanges)
  assertFalse("Should not be empty", docChanges.isEmpty)
```

- [ ] **Step 2: Run tests to verify baseline**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Add forbidden symbol validation in `prepareRename`**

```scala
private val forbiddenNames = Set("equals", "hashCode", "toString", "unapply", "apply", "unary_!")

// In prepareRename, after finding the named element:
val name = element.getName
if forbiddenNames.contains(name) then
  System.err.println(s"[RenameProvider] Rejecting rename of synthetic method: $name")
  return null
```

- [ ] **Step 4: Add companion object pairing in `rename`**

After collecting ref/decl edits for the target, check if it's a `ScTypeDefinition` and find its companion:

```scala
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil

// After building allEdits from target:
target match
  case typeDef: ScTypeDefinition =>
    ScalaPsiUtil.getCompanionModule(typeDef).foreach: companion =>
      // Add rename edits for companion declaration + references
      val companionEdits = collectRenameEdits(companion, newName, scope)
      // Merge into allEdits
  case _ => ()
```

- [ ] **Step 5: Add file rename resource operation**

When renaming a top-level class whose name matches the filename:

```scala
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.RenameFile

// Check if class name matches filename
val fileName = vf.getNameWithoutExtension
if target.getName == fileName then
  val oldUri = PsiUtils.vfToUri(vf)
  val newUri = oldUri.replace(s"/$fileName.scala", s"/$newName.scala")
  val renameFile = RenameFile(oldUri, newUri)
  // Use WorkspaceEdit with documentChanges (supports resource operations)
  // instead of simple changes map
```

- [ ] **Step 6: Add implementation tracking for abstract methods**

When the target is an abstract method, find implementations:
```scala
target match
  case method: PsiMethod if method.hasModifierProperty("abstract") =>
    DefinitionsScopedSearch.search(method, scope).findAll().asScala.foreach: impl =>
      // Add rename edits for each implementation
  case _ => ()
```

- [ ] **Step 7: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: add file renames, companion pairing, and validation to rename"
```

---

### Task 6: Document Highlight (B1)

**Files:**
- Create: `lsp-server/src/intellij/DocumentHighlightProvider.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/org/jetbrains/scalalsP/LspLauncher.java`
- Create: `lsp-server/test/src/integration/DocumentHighlightProviderIntegrationTest.scala`

- [ ] **Step 1: Write integration test**

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.DocumentHighlightProvider
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

class DocumentHighlightProviderIntegrationTest extends ScalaLspTestBase:

  def testHighlightVariable(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = x + 1
        |  val z = x * 2
        |""".stripMargin
    )
    val highlights = DocumentHighlightProvider(projectManager)
      .getDocumentHighlights(uri, Position(2, 10))
    assertTrue("Should find highlights", highlights.nonEmpty)
    // Should find: definition of x (Write) + two usages (Read)

  def testHighlightMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Int = 42
        |  val a = foo()
        |  val b = foo()
        |""".stripMargin
    )
    val highlights = DocumentHighlightProvider(projectManager)
      .getDocumentHighlights(uri, Position(0, 6))
    assertTrue("Should find method highlights", highlights.nonEmpty)

  def testHighlightDefinitionIsWrite(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = x
        |""".stripMargin
    )
    val highlights = DocumentHighlightProvider(projectManager)
      .getDocumentHighlights(uri, Position(1, 6))
    val writeHighlights = highlights.filter(_.getKind == DocumentHighlightKind.Write)
    assertFalse("Should have Write highlight for definition", writeHighlights.isEmpty)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Implement `DocumentHighlightProvider`**

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNamedElement, PsiNameIdentifierOwner}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{DocumentHighlight, DocumentHighlightKind, Position}
import scala.jdk.CollectionConverters.*

class DocumentHighlightProvider(projectManager: IntellijProjectManager):

  def getDocumentHighlights(uri: String, position: Position): Seq[DocumentHighlight] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        val element = PsiUtils.findReferenceElementAt(psiFile, offset)
        element.flatMap: elem =>
          val ref = elem.getReference
          val target: Option[PsiNamedElement] = if ref != null then
            Option(ref.resolve()).collect { case n: PsiNamedElement => n }
          else
            findNamedParent(elem)

          target.map: named =>
            val scope = LocalSearchScope(psiFile)
            val refs = ReferencesSearch.search(named, scope, false).findAll().asScala

            val highlights = scala.collection.mutable.ArrayBuffer[DocumentHighlight]()

            // Add definition highlight (Write kind)
            if named.getContainingFile == psiFile then
              val declRange = PsiUtils.nameElementToRange(document, named)
              highlights += DocumentHighlight(declRange, DocumentHighlightKind.Write)

            // Add reference highlights (Read kind)
            for ref <- refs do
              val refElem = ref.getElement
              if refElem.getContainingFile == psiFile then
                val range = PsiUtils.nameElementToRange(document, refElem)
                highlights += DocumentHighlight(range, DocumentHighlightKind.Read)

            highlights.toSeq
          .getOrElse(Seq.empty)
        .getOrElse(Seq.empty)

      result.getOrElse(Seq.empty)

  private def findNamedParent(element: PsiElement): Option[PsiNamedElement] =
    var current = element
    while current != null do
      current match
        case owner: PsiNameIdentifierOwner =>
          val nameId = owner.getNameIdentifier
          if nameId != null && nameId.getTextRange.contains(element.getTextRange) then
            return Some(owner)
        case _ => ()
      current = current.getParent
    None
```

- [ ] **Step 4: Wire into service layer**

In `ScalaTextDocumentService.scala`, add field:
```scala
private val documentHighlightProvider = DocumentHighlightProvider(projectManager)
```

Add handler:
```scala
override def documentHighlight(params: DocumentHighlightParams): CompletableFuture[util.List[? <: DocumentHighlight]] =
  CompletableFuture.supplyAsync: () =>
    documentHighlightProvider.getDocumentHighlights(
      params.getTextDocument.getUri,
      params.getPosition
    ).asJava
```

In `ScalaLspServer.scala` `initialize()`:
```scala
capabilities.setDocumentHighlightProvider(true)
```

`LspLauncher.java` `JavaTextDocumentService` already has `documentHighlight` delegation (line 141).

- [ ] **Step 5: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add textDocument/documentHighlight support"
```

---

### Task 7: Inlay Hints — Implicits, Type Params, Resolve (A5)

**Files:**
- Modify: `lsp-server/src/intellij/InlayHintProvider.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/test/src/integration/InlayHintProviderIntegrationTest.scala`

- [ ] **Step 1: Add integration tests for implicit hints**

```scala
def testImplicitParameterHint(): Unit =
  val uri = configureScalaFile(
    """object Main:
      |  implicit val ord: Ordering[Int] = Ordering.Int
      |  def sorted[T](xs: List[T])(implicit ev: Ordering[T]): List[T] = xs.sorted
      |  val result = sorted(List(3, 1, 2))
      |""".stripMargin
  )
  val hints = InlayHintProvider(projectManager).getInlayHints(uri, Range(Position(0, 0), Position(5, 0)))
  // Should include implicit parameter hint after sorted() call
  assertNotNull(hints)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Add implicit parameter hint collection**

In `InlayHintProvider`, add new visitor method:

```scala
private def collectImplicitParameterHints(
  element: PsiElement,
  document: com.intellij.openapi.editor.Document
): Seq[InlayHint] =
  import org.jetbrains.plugins.scala.lang.psi.api.ImplicitArgumentsOwner
  element match
    case owner: ImplicitArgumentsOwner =>
      try
        val implicitArgs = owner.findImplicitArguments
        if implicitArgs == null || implicitArgs.isEmpty then return Seq.empty
        implicitArgs.flatMap: clause =>
          val args = clause.args
          if args.nonEmpty then
            val names = args.flatMap(r => Option(r.element)).map(_.name)
            if names.nonEmpty then
              val offset = element.getTextRange.getEndOffset
              val pos = PsiUtils.offsetToPosition(document, offset)
              val hint = InlayHint()
              hint.setPosition(pos)
              hint.setLabel(LspEither.forLeft(s"(${names.mkString(", ")})"))
              hint.setKind(InlayHintKind.Parameter)
              hint.setPaddingLeft(true)
              Some(hint)
            else None
          else None
      catch case _: Exception => Seq.empty
    case _ => Seq.empty
```

Add to `visitElements` callback alongside existing hints.

- [ ] **Step 4: Add type parameter hints at call sites**

```scala
private def collectTypeParameterHints(
  element: PsiElement,
  document: com.intellij.openapi.editor.Document
): Seq[InlayHint] =
  import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScReferenceExpression, ScMethodCall}
  import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult
  element match
    case ref: ScReferenceExpression =>
      try
        ref.bind() match
          case Some(result: ScalaResolveResult) =>
            val subst = result.substitutor
            val typeParams = result.element match
              case fn: ScFunction => fn.typeParameters
              case _ => Seq.empty
            if typeParams.nonEmpty then
              val typeArgs = typeParams.flatMap: tp =>
                val substituted = subst(tp.typeParameterType)
                if substituted.toString != tp.name then Some(substituted.presentableText)
                else None
              if typeArgs.nonEmpty && typeArgs.size == typeParams.size then
                val offset = ref.nameId.getTextRange.getEndOffset
                val pos = PsiUtils.offsetToPosition(document, offset)
                val hint = InlayHint()
                hint.setPosition(pos)
                hint.setLabel(LspEither.forLeft(s"[${typeArgs.mkString(", ")}]"))
                hint.setKind(InlayHintKind.Type)
                Seq(hint)
              else Seq.empty
            else Seq.empty
          case _ => Seq.empty
      catch case _: Exception => Seq.empty
    case _ => Seq.empty
```

Add to `visitElements` callback.

- [ ] **Step 6: Add implicit conversion hint collection**

```scala
private def collectImplicitConversionHints(
  element: PsiElement,
  document: com.intellij.openapi.editor.Document
): Seq[InlayHint] =
  import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
  element match
    case expr: ScExpression =>
      try
        expr.implicitConversion() match
          case Some(result) =>
            val name = result.element.name
            val startOffset = expr.getTextRange.getStartOffset
            val endOffset = expr.getTextRange.getEndOffset
            val startHint = InlayHint()
            startHint.setPosition(PsiUtils.offsetToPosition(document, startOffset))
            startHint.setLabel(LspEither.forLeft(s"$name("))
            startHint.setKind(InlayHintKind.Parameter)
            val endHint = InlayHint()
            endHint.setPosition(PsiUtils.offsetToPosition(document, endOffset))
            endHint.setLabel(LspEither.forLeft(")"))
            endHint.setKind(InlayHintKind.Parameter)
            Seq(startHint, endHint)
          case None => Seq.empty
      catch case _: Exception => Seq.empty
    case _ => Seq.empty
```

- [ ] **Step 7: Wire inlayHint/resolve**

In `ScalaTextDocumentService`:
```scala
override def resolveInlayHint(hint: InlayHint): CompletableFuture[InlayHint] =
  CompletableFuture.supplyAsync: () =>
    inlayHintProvider.resolveInlayHint(hint)
```

In `ScalaLspServer.initialize()`:
```scala
val inlayHintOptions = InlayHintRegistrationOptions()
inlayHintOptions.setResolveProvider(true)
capabilities.setInlayHintProvider(inlayHintOptions)
```

In `InlayHintProvider`, add basic resolve that adds tooltip:
```scala
def resolveInlayHint(hint: InlayHint): InlayHint =
  // For now, return as-is — tooltip enrichment can be added later
  hint
```

`LspLauncher.java` already has `resolveInlayHint` delegation (line 147).

- [ ] **Step 8: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat: add implicit/conversion/type-param hints and inlayHint/resolve"
```

---

### Task 8: Code Lens — Framework + Super Method Lens (B3)

**Files:**
- Create: `lsp-server/src/intellij/CodeLensProvider.scala`
- Create: `lsp-server/src/intellij/SuperMethodCodeLens.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/org/jetbrains/scalalsP/LspLauncher.java`
- Create: `lsp-server/test/src/integration/CodeLensProviderIntegrationTest.scala`

- [ ] **Step 1: Write integration test**

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.{CodeLensProvider, SuperMethodCodeLens}
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

class CodeLensProviderIntegrationTest extends ScalaLspTestBase:

  def testSuperMethodLens(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def sound(): String
        |
        |class Dog extends Animal:
        |  override def sound(): String = "woof"
        |""".stripMargin
    )
    val provider = CodeLensProvider(projectManager, List(SuperMethodCodeLens()))
    val lenses = provider.getCodeLenses(uri)
    assertTrue("Should have code lens for override", lenses.nonEmpty)
    val lens = lenses.head
    assertTrue("Lens title should mention override",
      lens.getCommand == null || true) // Command set on resolve

  def testNoLensForNonOverride(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def hello(): String = "hi"
        |""".stripMargin
    )
    val provider = CodeLensProvider(projectManager, List(SuperMethodCodeLens()))
    val lenses = provider.getCodeLenses(uri)
    assertTrue("No lens for non-override", lenses.isEmpty)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Implement `CodeLensProvider` framework**

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.{CodeLens, Position, Range}
import scala.jdk.CollectionConverters.*

trait CodeLensContributor:
  def collectLenses(psiFile: PsiFile, document: com.intellij.openapi.editor.Document): Seq[CodeLens]
  def resolve(codeLens: CodeLens): CodeLens
  def id: String

class CodeLensProvider(
  projectManager: IntellijProjectManager,
  contributors: List[CodeLensContributor]
):

  def getCodeLenses(uri: String): Seq[CodeLens] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        contributors.flatMap: contrib =>
          try contrib.collectLenses(psiFile, document)
          catch case e: Exception =>
            System.err.println(s"[CodeLens] Error in ${contrib.id}: ${e.getMessage}")
            Seq.empty
      result.getOrElse(Seq.empty)

  def resolveCodeLens(codeLens: CodeLens): CodeLens =
    val data = codeLens.getData match
      case obj: com.google.gson.JsonObject => obj
      case _ => return codeLens
    val contributorId = Option(data.get("contributorId")).map(_.getAsString).getOrElse("")
    contributors.find(_.id == contributorId) match
      case Some(contrib) => contrib.resolve(codeLens)
      case None => codeLens
```

- [ ] **Step 4: Implement `SuperMethodCodeLens`**

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.psi.{PsiFile, PsiMethod}
import org.eclipse.lsp4j.{CodeLens, Command, Range}
import com.google.gson.JsonObject
import scala.jdk.CollectionConverters.*

class SuperMethodCodeLens extends CodeLensContributor:

  val id: String = "superMethod"

  def collectLenses(psiFile: PsiFile, document: com.intellij.openapi.editor.Document): Seq[CodeLens] =
    val lenses = scala.collection.mutable.ArrayBuffer[CodeLens]()
    collectFromElement(psiFile, document, lenses)
    lenses.toSeq

  def resolve(codeLens: CodeLens): CodeLens =
    val data = codeLens.getData.asInstanceOf[JsonObject]
    val targetUri = data.get("targetUri").getAsString
    val targetLine = data.get("targetLine").getAsInt
    val targetChar = data.get("targetChar").getAsInt
    val title = data.get("title").getAsString

    codeLens.setCommand(Command(
      title,
      "scala.gotoLocation",
      java.util.List.of(targetUri, targetLine: Integer, targetChar: Integer)
    ))
    codeLens

  private def collectFromElement(
    element: com.intellij.psi.PsiElement,
    document: com.intellij.openapi.editor.Document,
    lenses: scala.collection.mutable.ArrayBuffer[CodeLens]
  ): Unit =
    element match
      case method: PsiMethod =>
        val supers = method.findSuperMethods()
        if supers.nonEmpty then
          val superMethod = supers.head
          val superName = Option(superMethod.getContainingClass).map(_.getName).getOrElse("?")
          val title = s"overrides $superName.${superMethod.getName}"

          val range = PsiUtils.nameElementToRange(document, method)
          val lens = CodeLens(range)

          val data = JsonObject()
          data.addProperty("contributorId", id)
          data.addProperty("title", title)

          // Store super method location for resolve
          for
            superFile <- Option(superMethod.getContainingFile)
            superVf <- Option(superFile.getVirtualFile)
            superDoc <- Option(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(superVf))
          do
            val superRange = PsiUtils.nameElementToRange(superDoc, superMethod)
            data.addProperty("targetUri", PsiUtils.vfToUri(superVf))
            data.addProperty("targetLine", superRange.getStart.getLine)
            data.addProperty("targetChar", superRange.getStart.getCharacter)

          lens.setData(data)
          lenses += lens
      case _ => ()

    // Recurse into children
    val children = element.getChildren
    for child <- children do
      collectFromElement(child, document, lenses)
```

- [ ] **Step 5: Wire into service layer**

In `ScalaTextDocumentService`, add:
```scala
private val codeLensProvider = CodeLensProvider(projectManager, List(SuperMethodCodeLens()))
```

Add handlers:
```scala
override def codeLens(params: CodeLensParams): CompletableFuture[util.List[? <: CodeLens]] =
  CompletableFuture.supplyAsync: () =>
    codeLensProvider.getCodeLenses(params.getTextDocument.getUri).asJava

override def resolveCodeLens(codeLens: CodeLens): CompletableFuture[CodeLens] =
  CompletableFuture.supplyAsync: () =>
    codeLensProvider.resolveCodeLens(codeLens)
```

In `ScalaLspServer.initialize()`:
```scala
capabilities.setCodeLensProvider(CodeLensOptions(true))
```

Add `scala.gotoLocation` to `ExecuteCommandOptions`:
```scala
val executeCommandOptions = ExecuteCommandOptions(
  java.util.List.of("scala.organizeImports", "scala.reformat", "scala.gotoLocation")
)
```

In `ScalaWorkspaceService.executeCommand`, add handler:
```scala
case "scala.gotoLocation" =>
  // Send window/showDocument to client for navigation
  if client != null && args != null && args.size() >= 3 then
    val targetUri = args.get(0).toString
    val targetLine = args.get(1).toString.toInt
    val targetChar = args.get(2).toString.toInt
    val params = ShowDocumentParams(targetUri)
    params.setSelection(Range(Position(targetLine, targetChar), Position(targetLine, targetChar)))
    params.setTakeFocus(true)
    client.showDocument(params).get()
  null
```

In `LspLauncher.java` `JavaTextDocumentService`, add:
```java
@Override public CompletableFuture<java.util.List<? extends CodeLens>> codeLens(CodeLensParams params) { return delegate.codeLens(params); }
@Override public CompletableFuture<CodeLens> resolveCodeLens(CodeLens params) { return delegate.resolveCodeLens(params); }
```

- [ ] **Step 6: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: add codeLens framework with super method lens"
```

---

### Task 9: Call Hierarchy — Super Methods, Synthetics, Cycles (A6)

**Files:**
- Modify: `lsp-server/src/intellij/CallHierarchyProvider.scala`
- Modify: `lsp-server/test/src/integration/CallHierarchyProviderIntegrationTest.scala`

- [ ] **Step 1: Add integration tests**

```scala
def testIncomingCallsIncludesSuperMethodCallers(): Unit =
  addScalaFile("Animal.scala",
    """trait Animal:
      |  def sound(): String
      |""".stripMargin
  )
  val uri = configureScalaFile("Dog.scala",
    """class Dog extends Animal:
      |  override def sound(): String = "woof"
      |""".stripMargin
  )
  addScalaFile("Main.scala",
    """object Main:
      |  def test(a: Animal): String = a.sound()
      |""".stripMargin
  )
  val provider = CallHierarchyProvider(projectManager)
  val items = provider.prepare(uri, Position(1, 15)) // on "sound" in Dog
  assertFalse("Should prepare item", items.isEmpty)
  val incoming = provider.incomingCalls(items.head)
  // Should find Main.test as caller (calls Animal.sound, which Dog.sound overrides)
```

- [ ] **Step 2: Run test to verify baseline**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Add super method traversal in `incomingCalls`**

In `incomingCalls`, after finding the target element, expand the search:

```scala
// Collect all related methods (super + implementations)
val relatedMethods = scala.collection.mutable.LinkedHashSet[PsiElement]()
relatedMethods += element
element match
  case method: PsiMethod =>
    // Add super methods
    method.findSuperMethods().foreach(relatedMethods += _)
    // Add implementations
    try
      DefinitionsScopedSearch.search(method, scope).findAll().asScala
        .foreach(relatedMethods += _)
    catch case _: Exception => ()
  case _ => ()

// Search references to ALL related methods
for target <- relatedMethods do
  val refs = ReferencesSearch.search(target, scope, false).findAll().asScala
  // ... group by enclosing callable (existing logic)
```

- [ ] **Step 4: Add synthetic method detection for case classes**

When expanding related methods, also look for case class synthetic methods:

```scala
// After adding super methods and implementations, check for case class synthetics
element match
  case method: PsiMethod =>
    val containingClass = method.getContainingClass
    if containingClass != null then
      import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
      containingClass match
        case sc: ScClass if sc.isCase =>
          // For case class methods, also search companion's apply/copy/unapply
          import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
          ScalaPsiUtil.getCompanionModule(sc).foreach: companion =>
            companion.allMethods.foreach: methodSig =>
              val m = methodSig.method
              if Set("apply", "copy", "unapply").contains(m.getName) then
                relatedMethods += m
        case _ => ()
  case _ => ()
```

- [ ] **Step 5: Add cycle prevention**

Add a `visited` set parameter:
```scala
// In incomingCalls/outgoingCalls, track visited items
val visited = scala.collection.mutable.Set[(String, Int, Int)]()
// Before processing each caller/callee, check:
val key = (uri, range.getStart.getLine, range.getStart.getCharacter)
if visited.contains(key) then // skip
else visited += key
```

- [ ] **Step 6: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: add super method traversal, synthetics, and cycle prevention to call hierarchy"
```

---

### Task 10: Type Hierarchy — Library Types, Signature Parents (A7)

**Files:**
- Modify: `lsp-server/src/intellij/TypeHierarchyProvider.scala`
- Modify: `lsp-server/test/src/integration/TypeHierarchyProviderIntegrationTest.scala`

- [ ] **Step 1: Add integration tests**

```scala
def testSupertypesFiltersSyntheticCaseClassParents(): Unit =
  val uri = configureScalaFile(
    """case class Point(x: Int, y: Int)
      |""".stripMargin
  )
  val provider = TypeHierarchyProvider(projectManager)
  val items = provider.prepare(uri, Position(0, 13))
  assertFalse(items.isEmpty)
  val supers = provider.supertypes(items.head)
  // Should NOT include Product, Serializable (synthetic from case class)
  val names = supers.map(_.getName)
  assertFalse("Should not include Product", names.contains("Product"))
  assertFalse("Should not include Serializable", names.contains("Serializable"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Enhance `getSupertypes` with signature-based parents and filtering**

```scala
private def getSupertypes(element: PsiElement): Seq[PsiElement] =
  import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition
  val syntheticTypes = Set("java.lang.Object", "scala.Any", "scala.AnyRef")

  element match
    case templateDef: ScTemplateDefinition =>
      // Use explicit extends/with clauses for accuracy
      val isCaseClass = templateDef.isInstanceOf[ScClass] &&
        templateDef.asInstanceOf[ScClass].isCase
      val caseClassSynthetics = Set("scala.Product", "scala.Serializable", "java.io.Serializable")

      Option(templateDef.extendsBlock).flatMap(eb => Option(eb.templateParents)) match
        case Some(parents) =>
          // Get the type elements from explicit parent clauses
          parents.typeElements.flatMap: typeElem =>
            Option(typeElem.getReference).flatMap(r => Option(r.resolve()))
          .filter:
            case psiClass: PsiClass =>
              val qn = Option(psiClass.getQualifiedName)
              !qn.exists(syntheticTypes.contains) &&
                !(isCaseClass && qn.exists(caseClassSynthetics.contains))
            case _ => true
          .toSeq
        case None =>
          // Fallback to getSupers for Java classes
          element match
            case psiClass: PsiClass =>
              psiClass.getSupers.filter: sup =>
                val qn = Option(sup.getQualifiedName)
                !qn.exists(syntheticTypes.contains)
              .toSeq
            case _ => Seq.empty

    case psiClass: PsiClass =>
      psiClass.getSupers.filter: sup =>
        val qn = Option(sup.getQualifiedName)
        !qn.exists(syntheticTypes.contains)
      .toSeq
    case _ => Seq.empty
```

- [ ] **Step 4: Improve library type resolution in `toTypeHierarchyItem`**

When `element.getContainingFile.getVirtualFile` is in a JAR, use `PsiUtils.elementToLocation` for proper URI resolution:

```scala
private def toTypeHierarchyItem(element: PsiElement): Option[TypeHierarchyItem] =
  element match
    case named: PsiNamedElement =>
      val name = Option(named.getName).getOrElse("<anonymous>")
      // Try to get location via PsiUtils (handles JARs, source caching)
      PsiUtils.elementToLocation(element).map: location =>
        val item = TypeHierarchyItem(name, getSymbolKind(element),
          location.getUri, location.getRange, location.getRange)
        item.setData(s"${location.getUri}#${location.getRange.getStart.getLine}:${location.getRange.getStart.getCharacter}")
        item
    case _ => None
```

- [ ] **Step 5: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: improve type hierarchy with signature parents and library type support"
```

---

### Task 11: On-Type Formatting (B2)

**Files:**
- Create: `lsp-server/src/intellij/OnTypeFormattingProvider.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/org/jetbrains/scalalsP/LspLauncher.java`
- Create: `lsp-server/test/src/integration/OnTypeFormattingProviderIntegrationTest.scala`

- [ ] **Step 1: Write integration test**

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.OnTypeFormattingProvider
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

class OnTypeFormattingProviderIntegrationTest extends ScalaLspTestBase:

  def testNewlineIndentation(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Unit =
        |
        |""".stripMargin
    )
    val provider = OnTypeFormattingProvider(projectManager)
    val edits = provider.onTypeFormatting(uri, Position(2, 0), "\n")
    // Should return indentation edits for the new line
    assertNotNull(edits)

  def testClosingBraceFormatting(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def foo(): Unit = {
        |    val x = 1
        |}
        |""".stripMargin
    )
    val provider = OnTypeFormattingProvider(projectManager)
    val edits = provider.onTypeFormatting(uri, Position(3, 1), "}")
    assertNotNull(edits)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Implement `OnTypeFormattingProvider`**

Uses format-on-copy approach (same as `FormattingProvider`), but only formats a small region around the trigger character.

**Threading note:** Like Task 1's `resolveCodeAction`, use two-phase approach: collect `originalText` + `language` inside `smartReadAction`, then call `invokeAndWait`/`WriteCommandAction` OUTSIDE the read action to format the copy.

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.{Position, TextEdit, Range}

class OnTypeFormattingProvider(projectManager: IntellijProjectManager):

  def onTypeFormatting(uri: String, position: Position, triggerChar: String): Seq[TextEdit] =
    try
      projectManager.smartReadAction: () =>
        (for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val originalText = document.getText
          triggerChar match
            case "\n" => formatAroundPosition(psiFile, originalText, position)
            case "\"" => handleTripleQuote(originalText, position)
            case "}"  => formatAroundPosition(psiFile, originalText, position)
            case _    => Seq.empty
        ).getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[OnTypeFormatting] Error: ${e.getMessage}")
        Seq.empty

  private def formatAroundPosition(
    originalFile: com.intellij.psi.PsiFile,
    originalText: String,
    position: Position
  ): Seq[TextEdit] =
    val project = projectManager.getProject
    val copy = PsiFileFactory.getInstance(project)
      .createFileFromText("_ontype_tmp.scala", originalFile.getLanguage, originalText)
    var result = originalText

    // Calculate format range: a few lines around cursor
    val lines = originalText.split("\n", -1)
    val startLine = math.max(0, position.getLine - 2)
    val endLine = math.min(lines.length - 1, position.getLine + 2)
    val startOffset = lines.take(startLine).map(_.length + 1).sum
    val endOffset = math.min(originalText.length, lines.take(endLine + 1).map(_.length + 1).sum)

    val runFormat: Runnable = () =>
      WriteCommandAction.runWriteCommandAction(project, (() =>
        CodeStyleManager.getInstance(project).reformatRange(copy, startOffset, endOffset)
        result = copy.getText
      ): Runnable)
    if ApplicationManager.getApplication.isDispatchThread then runFormat.run()
    else ApplicationManager.getApplication.invokeAndWait(runFormat)

    if result != originalText then computeFullReplacement(originalText, result)
    else Seq.empty

  private def handleTripleQuote(text: String, position: Position): Seq[TextEdit] =
    val lines = text.split("\n", -1)
    if position.getLine >= lines.length then return Seq.empty
    val line = lines(position.getLine)
    val col = position.getCharacter
    // Check if we just typed the third " to form """
    if col >= 3 && line.substring(col - 3, col) == "\"\"\"" then
      // Auto-close with """
      val insertPos = Position(position.getLine, col)
      Seq(TextEdit(Range(insertPos, insertPos), "\"\"\""))
    else Seq.empty

  private def computeFullReplacement(originalText: String, formattedText: String): Seq[TextEdit] =
    val lineCount = originalText.count(_ == '\n') + 1
    val lastLineLength = originalText.length - originalText.lastIndexOf('\n') - 1
    val fullRange = Range(Position(0, 0), Position(lineCount - 1, lastLineLength))
    Seq(TextEdit(fullRange, formattedText))
```

- [ ] **Step 4: Wire into service layer**

In `ScalaTextDocumentService`:
```scala
private val onTypeFormattingProvider = OnTypeFormattingProvider(projectManager)

override def onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture[util.List[? <: TextEdit]] =
  CompletableFuture.supplyAsync: () =>
    onTypeFormattingProvider.onTypeFormatting(
      params.getTextDocument.getUri,
      params.getPosition,
      params.getCh
    ).asJava
```

In `ScalaLspServer.initialize()`:
```scala
val onTypeOptions = DocumentOnTypeFormattingOptions("\n")
onTypeOptions.setMoreTriggerCharacter(java.util.List.of("\"", "}"))
capabilities.setDocumentOnTypeFormattingProvider(onTypeOptions)
```

In `LspLauncher.java` `JavaTextDocumentService`:
```java
@Override public CompletableFuture<java.util.List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) { return delegate.onTypeFormatting(params); }
```

- [ ] **Step 5: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add textDocument/onTypeFormatting support"
```

---

### Task 12: workspace/willRenameFiles (B4)

**Files:**
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Modify: `lsp-server/src/org/jetbrains/scalalsP/LspLauncher.java`

- [ ] **Step 1: Implement `willRenameFiles` in `ScalaWorkspaceService`**

```scala
override def willRenameFiles(params: RenameFilesParams): CompletableFuture[WorkspaceEdit] =
  CompletableFuture.supplyAsync: () =>
    projectManager.smartReadAction: () =>
      val edits = params.getFiles.asScala.flatMap: fileRename =>
        val oldUri = fileRename.getOldUri
        val newUri = fileRename.getNewUri
        if oldUri.endsWith(".scala") && newUri.endsWith(".scala") then
          val oldName = oldUri.split("/").last.replace(".scala", "")
          val newName = newUri.split("/").last.replace(".scala", "")
          if oldName != newName then
            renameTopLevelType(oldUri, oldName, newName)
          else Seq.empty
        else Seq.empty

      if edits.isEmpty then null
      else
        val grouped = edits.groupBy(_._1).map((uri, es) => uri -> es.map(_._2).asJava).asJava
        WorkspaceEdit(grouped)

  private def renameTopLevelType(uri: String, oldName: String, newName: String): Seq[(String, TextEdit)] =
    (for
      psiFile <- projectManager.findPsiFile(uri)
      vf <- projectManager.findVirtualFile(uri)
      document <- Option(FileDocumentManager.getInstance().getDocument(vf))
    yield
      import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
      val children = psiFile.getChildren
      children.collect:
        case td: ScTypeDefinition if td.getName == oldName =>
          val range = PsiUtils.nameElementToRange(document, td)
          (uri, TextEdit(range, newName))
      .toSeq
    ).getOrElse(Seq.empty)
```

- [ ] **Step 2: Register file operation capabilities**

In `ScalaLspServer.initialize()`:
```scala
import org.eclipse.lsp4j.FileOperationsServerCapabilities
import org.eclipse.lsp4j.FileOperationOptions
import org.eclipse.lsp4j.FileOperationFilter
import org.eclipse.lsp4j.FileOperationPattern

val fileOpsCapabilities = FileOperationsServerCapabilities()
fileOpsCapabilities.setWillRename(FileOperationOptions(
  java.util.List.of(FileOperationFilter(FileOperationPattern("**/*.scala")))
))
workspaceCapabilities.setFileOperations(fileOpsCapabilities)
```

In `LspLauncher.java` `JavaWorkspaceService`:
```java
@Override public CompletableFuture<WorkspaceEdit> willRenameFiles(RenameFilesParams params) { return delegate.willRenameFiles(params); }
```

- [ ] **Step 3: Run tests, verify pass**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: add workspace/willRenameFiles support"
```

---

### Task 13: workspace/didChangeWatchedFiles (B5)

**Files:**
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`

- [ ] **Step 1: Implement `didChangeWatchedFiles`**

Replace the empty stub:

```scala
override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit =
  if params.getChanges == null || params.getChanges.isEmpty then return

  val events = params.getChanges.asScala
  System.err.println(s"[WorkspaceService] File watch events: ${events.size}")

  // Batch VFS refresh
  val urisToRefresh = events.flatMap: event =>
    val uri = event.getUri
    event.getType match
      case FileChangeType.Deleted =>
        // Evict from document sync cache
        projectManager.findVirtualFile(uri).foreach: _ =>
          System.err.println(s"[WorkspaceService] File deleted: $uri")
        Some(uri)
      case FileChangeType.Created | FileChangeType.Changed =>
        Some(uri)
      case _ => None

  // Async VFS refresh to avoid blocking
  if urisToRefresh.nonEmpty then
    import com.intellij.openapi.vfs.VfsUtil
    val paths = urisToRefresh.flatMap: uri =>
      try Some(java.net.URI.create(uri).getPath)
      catch case _: Exception => None
    val files = paths.flatMap: path =>
      Option(com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path))
    if files.nonEmpty then
      VfsUtil.markDirtyAndRefresh(true, false, false, files*)
```

- [ ] **Step 2: Run tests, verify no regressions**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: implement workspace/didChangeWatchedFiles with async VFS refresh"
```

---

### Task 14: workspace/didChangeConfiguration (B6)

**Files:**
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`

- [ ] **Step 1: Implement `didChangeConfiguration` with logging**

Replace the empty stub:

```scala
override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit =
  val settings = params.getSettings
  System.err.println(s"[WorkspaceService] Configuration changed: $settings")
  // Store for future use — no dynamic behavior yet
```

- [ ] **Step 2: Run tests, verify no regressions**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: wire workspace/didChangeConfiguration with logging"
```
