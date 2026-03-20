# Remaining LSP Features Design Spec

**Date:** 2026-03-20
**Status:** Approved
**Approach:** Incremental by complexity — each feature is a self-contained unit with tests

## Overview

With VS Code now supported as a client, we implement 6 remaining LSP features to bring the server closer to full LSP coverage. Implementation order: Formatting, Completion Resolve, Signature Help, Document Link, Workspace Folders, Semantic Tokens.

## Feature 1: Formatting

**LSP methods:** `textDocument/formatting`, `textDocument/rangeFormatting`

**New file:** `lsp-server/src/intellij/FormattingProvider.scala`

- `getFormatting(uri: String): Seq[TextEdit]` — reformats entire file via `CodeStyleManager.reformat()`, returns diff as text edits
- `getRangeFormatting(uri: String, range: Range): Seq[TextEdit]` — reformats selected range via `CodeStyleManager.reformatRange()`
- Both methods capture document text before write action, run `CodeStyleManager` in a `WriteCommandAction`, capture after, return full-replacement text edit

**Capability registration:**
```scala
capabilities.setDocumentFormattingProvider(true)
capabilities.setDocumentRangeFormattingProvider(true)
```

**Wiring:** Override `formatting()` and `rangeFormatting()` in `ScalaTextDocumentService`. Same `CompletableFuture.supplyAsync` + write action pattern as existing workspace reformat.

**Reuse:** Delegates to the same `CodeStyleManager` API already used by `scala.reformat` in `ScalaWorkspaceService`.

## Feature 2: Completion Resolve

**LSP method:** `completionItem/resolve`

**Changes to `CompletionProvider.scala`:**

- `toLspCompletionItem()` becomes lean: only `label`, `kind`, `sortText`, `insertText`, and a `data` field (JSON object with lookup string + index) for identification
- Remove `getAutoImportEdit()` and `detail` computation from initial pass
- New method: `resolveCompletion(item: CompletionItem): CompletionItem` — re-locates the `LookupElement` from cache and populates:
  - `detail` — type signature from `LookupElementPresentation`
  - `documentation` — ScalaDoc/JavaDoc via `LanguageDocumentation`
  - `additionalTextEdits` — auto-import edits

**Caching:** Store `LookupElement` array after `getCompletions()` in a short-lived cache (keyed by request, cleared on next completion call or after 30s TTL). Resolve looks up by index.

**Capability registration:**
```scala
val completionOptions = CompletionOptions(true, java.util.List.of(".", " "))  // resolveProvider=true
```

**Wiring:** Override `resolveCompletionItem()` in `ScalaTextDocumentService`.

## Feature 3: Signature Help

**LSP method:** `textDocument/signatureHelp`

**New file:** `lsp-server/src/intellij/SignatureHelpProvider.scala`

- `getSignatureHelp(uri: String, position: Position): Option[SignatureHelp]`
- Primary approach: Use `ParameterInfoHandler.EP_NAME.getExtensionList` filtered by Scala language to extract parameter info items (overload variants)
- Locates the argument list context at cursor position (enclosing method call)
- Maps each variant to `SignatureInformation` with `ParameterInformation` entries
- Sets `activeSignature` (best-matching overload) and `activeParameter` (cursor position in arg list)

**Fallback:** If `ParameterInfoHandler` proves difficult outside editor context, fall back to resolving the method reference at/before `(` and extracting signatures via reflection on Scala plugin's `ScFunction` class.

**Capability registration:**
```scala
val signatureHelpOptions = SignatureHelpOptions(java.util.List.of("(", ","))
capabilities.setSignatureHelpProvider(signatureHelpOptions)
```

**Wiring:** Override `signatureHelp()` in `ScalaTextDocumentService`.

## Feature 4: Document Link

**LSP method:** `textDocument/documentLink`

**New file:** `lsp-server/src/intellij/DocumentLinkProvider.scala`

- `getDocumentLinks(uri: String): Seq[DocumentLink]`
- Operates on raw document text via regex scanning (no PSI needed for most cases)

**Link types:**

1. **URLs in comments/strings:** Regex `https?://[^\s"')>]+`. Target is the URL itself.
2. **File paths in strings:** Detect string literals containing relative paths (e.g., `"src/main/resources/app.conf"`). Resolve against project root. Target is `file://` URI. Only emit link if file exists on disk.
3. **SBT dependency coordinates:** Pattern `"org" %% "artifact" % "version"` in `.sbt`/`.scala` files. Link to `https://search.maven.org/artifact/{group}/{artifact}/{version}`. Regex: `"([^"]+)"\s+%%?\s+"([^"]+)"\s+%\s+"([^"]+)"`.

**Capability registration:**
```scala
capabilities.setDocumentLinkProvider(DocumentLinkOptions())
```

**Wiring:** Override `documentLink()` in `ScalaTextDocumentService`.

## Feature 5: Workspace Folders

**LSP method:** `workspace/didChangeWorkspaceFolders`

**Changes to `IntellijProjectManager`:**

- Currently holds a single `project` field. Add `projects: TrieMap[String, Project]` to map folder paths to projects.
- `findPsiFile(uri)` searches across all open projects by matching the file's path against project base paths.
- `getProject` remains for single-project compatibility; add `getProjectForUri(uri: String): Project` for multi-project routing.

**Changes to `ScalaWorkspaceService`:**

- Override `didChangeWorkspaceFolders(params)`:
  - For each added folder: `projectManager.openProject(folderPath)`
  - For each removed folder: `projectManager.closeProject(folderPath)`

**Changes to `ScalaLspServer.initialize()`:**

- Declare workspace folder support:
```scala
val workspaceFolderOptions = WorkspaceFoldersOptions()
workspaceFolderOptions.setSupported(true)
workspaceFolderOptions.setChangeNotifications(true)
val workspaceCapabilities = WorkspaceServerCapabilities(workspaceFolderOptions)
capabilities.setWorkspace(workspaceCapabilities)
```
- If `params.getWorkspaceFolders` contains multiple folders, open all during initialize.

**Provider impact:** All providers receive `projectManager` — the routing change in `getProjectForUri` is transparent as long as `findPsiFile`/`findVirtualFile` handle multi-project lookup internally.

## Feature 6: Semantic Tokens

**LSP methods:** `textDocument/semanticTokens/full`, `textDocument/semanticTokens/range`

**New file:** `lsp-server/src/intellij/SemanticTokensProvider.scala`

- `getSemanticTokensFull(uri: String): SemanticTokens` — returns all tokens for the file
- `getSemanticTokensRange(uri: String, range: Range): SemanticTokens` — returns tokens within range

**Token extraction:**

- Use `DaemonCodeAnalyzerImpl.getHighlights()` with `HighlightSeverity.INFORMATION` threshold to capture syntax highlighting data
- Each `HighlightInfo` has a `textAttributesKey` — map `TextAttributesKey.getExternalName()` to LSP token types

**Scala-rich token mapping:**

| IntelliJ TextAttributesKey | LSP Token Type | LSP Modifiers |
|---|---|---|
| `SCALA_KEYWORD` | `keyword` | — |
| `SCALA_CLASS` | `class` | — |
| `SCALA_TRAIT` | `interface` | `abstract` |
| `SCALA_OBJECT` | `class` | `static` |
| `SCALA_CASE_CLASS` | `class` | `readonly` |
| `SCALA_TYPE_ALIAS` | `type` | — |
| `SCALA_LOCAL_VARIABLE` | `variable` | — |
| `SCALA_PARAMETER` | `parameter` | — |
| `SCALA_METHOD` / `SCALA_METHOD_CALL` | `method` | — |
| `SCALA_IMPLICIT_*` | (base type) | `modification` |
| `SCALA_LAZY` | (base type) | `lazy` (custom) |
| `SCALA_TYPE_PARAMETER` | `typeParameter` | — |
| `SCALA_STRING` | `string` | — |
| `SCALA_NUMBER` | `number` | — |
| `SCALA_LINE_COMMENT` / `SCALA_BLOCK_COMMENT` | `comment` | — |
| `SCALA_DOC_COMMENT` | `comment` | `documentation` |

**Encoding:** Delta-encoded integer array per LSP spec: `[deltaLine, deltaStartChar, length, tokenType, tokenModifiers]`. Sort highlights by position and compute deltas.

**Capability registration:**
```scala
val tokenTypes = java.util.List.of(
  "keyword", "type", "class", "interface", "enum", "method",
  "property", "variable", "parameter", "typeParameter",
  "string", "number", "comment", "function"
)
val tokenModifiers = java.util.List.of(
  "declaration", "static", "abstract", "readonly",
  "modification", "documentation", "lazy"
)
val legend = SemanticTokensLegend(tokenTypes, tokenModifiers)
val semanticTokensOptions = SemanticTokensWithRegistrationOptions(legend)
semanticTokensOptions.setFull(true)
semanticTokensOptions.setRange(true)
capabilities.setSemanticTokensProvider(semanticTokensOptions)
```

**Wiring:** Override `semanticTokensFull()` and `semanticTokensRange()` in `ScalaTextDocumentService`.

**Key risk:** IntelliJ's `TextAttributesKey` names are internal to the Scala plugin. We discover actual key names at runtime and maintain best-effort mapping, falling back to skipping unmapped keys.

## Implementation Order

1. **Formatting** — simplest, reuses existing `CodeStyleManager` logic
2. **Completion Resolve** — refactor of existing provider, high performance impact
3. **Signature Help** — new provider, moderate complexity
4. **Document Link** — new provider, regex-based, self-contained
5. **Workspace Folders** — cross-cutting change to `IntellijProjectManager`
6. **Semantic Tokens** — most complex, depends on understanding IntelliJ highlighting internals

## Files Changed Summary

**New files (4):**
- `lsp-server/src/intellij/FormattingProvider.scala`
- `lsp-server/src/intellij/SignatureHelpProvider.scala`
- `lsp-server/src/intellij/DocumentLinkProvider.scala`
- `lsp-server/src/intellij/SemanticTokensProvider.scala`

**Modified files (4):**
- `lsp-server/src/ScalaLspServer.scala` — capability registration for all 6 features
- `lsp-server/src/ScalaTextDocumentService.scala` — new provider fields + override methods
- `lsp-server/src/ScalaWorkspaceService.scala` — `didChangeWorkspaceFolders` handler
- `lsp-server/src/intellij/CompletionProvider.scala` — refactor for lazy resolve
- `lsp-server/src/intellij/IntellijProjectManager.scala` — multi-project support

**Test files (6 new, per feature):**
- Integration + E2E tests following existing patterns (`ScalaLspTestBase`, `E2eTestBase`)
