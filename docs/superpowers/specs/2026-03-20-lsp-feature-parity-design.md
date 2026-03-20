# LSP Feature Parity with Metals — Design Spec

**Date:** 2026-03-20
**Scope:** Standard LSP feature gaps + existing feature quality improvements
**Reference:** Metals LSP implementation at `/Users/hoangmei/Work/metals`

---

## Overview

Compare intellij-scala-lsp with Metals, fill gaps in two categories:
- **Part A:** Improve quality of 8 existing features
- **Part B:** Add 6 new LSP features

Approach C: provider-per-feature with codeLens framework for extensibility.

---

## Part A: Existing Feature Quality Improvements

### A1. Code Actions — Make Them Actually Work

**Problem:** `CodeActionProvider` returns `CodeAction` objects with titles but no workspace edits and no commands. Client shows the action in lightbulb menu, clicking does nothing.

**Fix:**

1. **All edits computed lazily via `resolveCodeAction`:** Both quick fixes and intention actions return only a title + `data` field (JSON with URI, offset, action class name/index). On `resolveCodeAction`, re-find the action, apply it on a PSI copy *outside* any read action (using the format-on-copy pattern: collect data in read action, then apply via `WriteCommandAction` + `invokeAndWait` outside it), diff the result, and return the workspace edit. This avoids deadlocking by calling `invokeAndWait` inside a read action.

2. **Wire `resolveCodeAction(CodeAction)`** in `ScalaTextDocumentService`, `ScalaLspServer` (set `resolveProvider = true` on `CodeActionOptions`), and add `resolveCodeAction` delegation in `JavaTextDocumentService` inside `LspLauncher.java`.

**Files changed:** `CodeActionProvider.scala`, `ScalaLspServer.scala`, `ScalaTextDocumentService.scala`, `LspLauncher.java`

---

### A2. Rename — File Renames, Companions, Validation

**Problem:** Rename only does text edits. Missing file renames when class name changes, no companion object handling, no forbidden symbol validation.

**Fix:**

1. **File rename resource operations:** When renaming a top-level class/trait/object whose name matches the filename, add a `RenameFile` resource operation to the `WorkspaceEdit`.

2. **Companion object/class pairing:** When renaming a class, check if the resolved element is a `ScTypeDefinition` (required type), then find its companion via `ScalaPsiUtil.getCompanionModule(typeDefinition: ScTypeDefinition): Option[ScTypeDefinition]`. Include both in the rename edit set.

3. **Forbidden symbol validation in `prepareRename`:** Reject renames of synthetic methods (`equals`, `hashCode`, `toString`, `unapply`, `apply`, `unary_!`). Return `null` from `prepareRename`.

4. **Implementation tracking:** When renaming an abstract method, find implementing methods via `DefinitionsScopedSearch` and include them in the edit.

**Files changed:** `RenameProvider.scala`

---

### A3. Signature Help — Return Types, Docs, Implicits

**Problem:** Text-based approach showing only `name(param1: Type1, param2: Type2)`. No return type, no documentation, no implicit parameter distinction.

**Fix:**

1. **Return type:** Extract from `ScFunction.returnType` or `PsiMethod.getReturnType()`. Append `: ReturnType` to signature label.

2. **Documentation:** Use `LanguageDocumentation` (same as `HoverProvider`) to get ScalaDoc. Set as `SignatureInformation.documentation` in markdown.

3. **Implicit parameter clauses:** Walk `ScFunction.paramClauses`, mark implicit/using clauses separately. Show as `(implicit x: Int)` or `(using ctx: Context)`.

4. **Active parameter across clauses:** Detect which parameter clause the cursor is in by counting balanced `()` groups. For curried functions `f(a)(b)`, show all clauses in a single `SignatureInformation.label` (Metals-style) and set `activeParameter` to the index across all clauses combined.

**Files changed:** `SignatureHelpProvider.scala`

---

### A4. Semantic Tokens — Operators, Deprecated, Escape Sequences

**Problem:** Missing operator token type (critical for Scala DSLs), no deprecated modifier, no string escape highlighting.

**Fix:**

1. **Operator token type:** Add `operator` to legend. Classify symbolic method names (last char is operator character) as operator tokens.

2. **Deprecated modifier:** Add `deprecated` to legend. Check resolved elements for `@deprecated` / `@java.lang.Deprecated` annotations.

3. **String escape sequences:** Within string literals, split the token into segments: non-escape text as `string` tokens and escape sequences (`\n`, `\t`, `\\`, `\uXXXX`) as `regexp` tokens. Do NOT emit overlapping tokens — the string literal must be decomposed into non-overlapping sub-tokens to comply with the LSP semantic tokens delta-encoding protocol.

**Files changed:** `SemanticTokensProvider.scala` (legend arrays + classification logic)

---

### A5. Inlay Hints — Implicits, Type Params, Conversions

**Problem:** Only type hints on definitions and parameter name hints. Missing implicit parameters, implicit conversions, type parameters at call sites.

**Fix:**

1. **Implicit parameter hints:** At call sites with implicit arguments, use `ImplicitArgumentsOwner.findImplicitArguments()` from Scala plugin. This returns `Seq[ImplicitArgumentsClause]` — unwrap each clause's `.args` to get `Seq[ScalaResolveResult]`, then extract resolved element names. Display as `(implicitArg1, implicitArg2)` after call.

2. **Implicit conversion hints:** Detect applied implicit conversions via `ScExpression.implicitConversion()` which returns `Option[ScalaResolveResult]`. Extract the conversion function name from the resolve result. Display as `conversionMethod(` before expression and `)` after.

3. **Type parameter hints at call sites:** From a `ScMethodCall` or `ScReferenceExpression`, get the `ScalaResolveResult` via `.bind()` or `.resolve()`, then access `result.substitutor` to get the `ScSubstitutor`. Extract type parameter mappings from the substitutor's type parameter → type bindings. Display as `[Int, String]` after method name.

4. **inlayHint/resolve:** Attach tooltip with full type info and location link for go-to-definition. Set `resolveProvider = true` in capabilities.

**Files changed:** `InlayHintProvider.scala`, `ScalaLspServer.scala`, `ScalaTextDocumentService.scala`, `LspLauncher.java`

---

### A6. Call Hierarchy — Super Methods, Synthetics, Cycle Prevention

**Problem:** No polymorphism awareness, no synthetic method detection, potential infinite loops.

**Fix:**

1. **Super method traversal:** For incoming calls, also find callers of super methods (`findSuperMethods()`) and overriding methods (`DefinitionsScopedSearch`). Union all callers.

2. **Synthetic method detection:** For case classes, include calls to synthetic `apply`, `copy`, `unapply` by checking companion object.

3. **Cycle prevention:** Track visited `(uri, range)` pairs in a `Set` during traversal.

**Files changed:** `CallHierarchyProvider.scala`

---

### A7. Type Hierarchy — Library Types, Signature-Based Parents

**Problem:** `getSupers()` may miss library types. Parent extraction is basic.

**Fix:**

1. **Signature-based parents:** Walk `ScTemplateDefinition.extendsBlock.templateParents` for explicit extends/with clauses with type arguments. Fall back to `getSupers()` for Java.

2. **Library type resolution:** Use existing `PsiUtils.elementToLocation()` (handles source JAR caching) to create navigable URIs for library parent types.

3. **Filter synthetic parents:** Also filter `Product`, `Serializable` from case class auto-derivation when not explicitly in extends clause.

**Files changed:** `TypeHierarchyProvider.scala`

---

### A8. Completion — Snippet Support

**Problem:** All completions use `PlainText`. Methods insert just the name.

**Fix:**

1. **Method snippets:** On resolve, set `insertTextFormat = Snippet` and generate `methodName($1)` / `methodName($1, $2)` etc.

2. **No snippet for overloads:** When method has overloads, insert name only.

3. **Apply during resolve:** Generate snippet text in `resolveCompletion()` to keep initial response fast.

**Files changed:** `CompletionProvider.scala`

---

## Part B: New LSP Features

### B1. textDocument/documentHighlight

**New file:** `DocumentHighlightProvider.scala`

- Find all references to symbol under cursor, scoped to current file via `ReferencesSearch.search()` with `LocalSearchScope(psiFile)`
- Mark definition as `DocumentHighlightKind.Write`, usages as `Read`
- Register in `ScalaLspServer.initialize()`: `capabilities.setDocumentHighlightProvider(true)`

---

### B2. textDocument/onTypeFormatting

**New file:** `OnTypeFormattingProvider.scala`

- Trigger characters: `\n`, `"`, `}`
- **Newline:** Format-on-copy the area around cursor (few lines), return indentation edits
- **Quote (`"`):** Detect third `"` forming `"""`, auto-close with `"""`
- **Brace (`}`):** Format-on-copy the block containing `}`, return indentation edits
- Register with `DocumentOnTypeFormattingOptions(firstTrigger = "\n", moreTrigger = List("\"", "}"))`

---

### B3. textDocument/codeLens + codeLens/resolve

**New files:** `CodeLensProvider.scala` (framework + registry), `SuperMethodCodeLens.scala` (first contributor)

**Framework:**
```
trait CodeLensContributor:
  def collectLenses(psiFile: PsiFile, document: Document): Seq[CodeLens]
  def resolve(codeLens: CodeLens): CodeLens
  def id: String
```

**Registry:** `CodeLensProvider` holds `List[CodeLensContributor]`, iterates all.

**SuperMethodCodeLens:**
- For each overriding method, show "overrides ClassName.methodName"
- Uses `PsiMethod.findSuperMethods()`
- On resolve: attach a server-side command registered in `ExecuteCommandOptions` (e.g., `scala.gotoLocation`) that the server handles via `workspace/executeCommand`, making it client-agnostic. The command sends a `window/showDocument` or `textDocument/definition`-style navigation to the client. Avoid VS Code-specific commands like `editor.action.goToLocations`.

**Registration:** `CodeLensOptions(resolveProvider = true)` in capabilities. Add `scala.gotoLocation` to `ExecuteCommandOptions`.

---

### B4. workspace/willRenameFiles

**In:** `ScalaWorkspaceService.scala`

- When `.scala` file renamed, find top-level class/object matching old filename
- Generate `TextEdit`s to rename the class/object to match new filename
- Return `WorkspaceEdit`
- Register via `FileOperationsServerCapabilities` on workspace capabilities:
  ```
  val fileOpsCapabilities = FileOperationsServerCapabilities()
  fileOpsCapabilities.setWillRename(FileOperationOptions(List(
    FileOperationFilter(FileOperationPattern("**/*.scala"))
  )))
  workspaceCapabilities.setFileOperations(fileOpsCapabilities)
  ```
- Override `willRenameFiles(RenameFilesParams)` in `ScalaWorkspaceService`
- Add `willRenameFiles` delegation in `JavaWorkspaceService` inside `LspLauncher.java`

---

### B5. workspace/didChangeWatchedFiles

**Enhance stub in:** `ScalaWorkspaceService.scala`

- On create/change events: use `VfsUtil.markDirtyAndRefresh(async = true, ...)` to avoid blocking on large projects
- On delete events: evict from `DocumentSyncManager` cache and mark VFS dirty
- Batch multiple events before triggering VFS refresh (events often arrive in bursts)
- Rely on client-side file watcher configuration (VS Code watches by default)

---

### B6. workspace/didChangeConfiguration

**Enhance stub in:** `ScalaWorkspaceService.scala`

- Log received settings
- Store in `ServerSettings` data object (infrastructure for future use)
- No dynamic behavior yet

---

## Wiring Checklist

For every new/changed LSP method:

1. Register capability in `ScalaLspServer.initialize()`
2. Add/update handler in `ScalaTextDocumentService` or `ScalaWorkspaceService`
3. Add/update delegate in `JavaTextDocumentService` or `JavaWorkspaceService` inside `LspLauncher.java`
4. Update VS Code `package.json` if client-side changes needed (codeLens, onTypeFormatting trigger chars)
5. Add integration tests

**New `JavaTextDocumentService` delegations required in `LspLauncher.java`:**
- `resolveCodeAction(CodeAction)` → for A1
- `onTypeFormatting(DocumentOnTypeFormattingParams)` → for B2
- `codeLens(CodeLensParams)` → for B3
- `resolveCodeLens(CodeLens)` → for B3

**New `JavaWorkspaceService` delegations required in `LspLauncher.java`:**
- `willRenameFiles(RenameFilesParams)` → for B4

---

## Implementation Order

Ordered by dependencies and value:

1. **A1 — Code Actions** (establishes the resolve pattern reused by A5, B3)
2. **A4 — Semantic Tokens** (independent, high visual impact)
3. **A8 — Completion Snippets** (independent, high UX impact)
4. **A3 — Signature Help** (independent)
5. **A2 — Rename** (independent)
6. **B1 — documentHighlight** (simple, standalone)
7. **A5 — Inlay Hints** (depends on resolve pattern from A1)
8. **B3 — codeLens** (depends on resolve pattern from A1)
9. **A6 — Call Hierarchy** (independent)
10. **A7 — Type Hierarchy** (independent)
11. **B2 — onTypeFormatting** (independent)
12. **B4 — willRenameFiles** (independent)
13. **B5 — didChangeWatchedFiles** (independent)
14. **B6 — didChangeConfiguration** (trivial, do last)

---

## Files Summary

**Modified:**
- `CodeActionProvider.scala` — workspace edits + resolve
- `RenameProvider.scala` — file renames, companions, validation
- `SignatureHelpProvider.scala` — return types, docs, implicits
- `SemanticTokensProvider.scala` — operator, deprecated, escapes
- `InlayHintProvider.scala` — implicit params/conversions, type params, resolve
- `CallHierarchyProvider.scala` — super methods, synthetics, cycles
- `TypeHierarchyProvider.scala` — library types, signature parents
- `CompletionProvider.scala` — snippet support
- `ScalaLspServer.scala` — capability registration
- `ScalaTextDocumentService.scala` — new handlers
- `ScalaWorkspaceService.scala` — willRenameFiles, didChangeWatchedFiles, didChangeConfiguration
- `LspLauncher.java` — Java delegate wrappers
- `vscode-extension/package.json` — codeLens, onTypeFormatting

**New:**
- `DocumentHighlightProvider.scala`
- `OnTypeFormattingProvider.scala`
- `CodeLensProvider.scala` (framework + registry)
- `SuperMethodCodeLens.scala`
