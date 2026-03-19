# Missing LSP Features: Rename, Type Hierarchy, Execute Command

## Context

The intellij-scala-lsp server currently implements 18 LSP features. This spec covers three high-value missing features prioritized for Claude Code as the primary client:

1. **Rename** — safe cross-file symbol renaming (highest value for an AI coding agent)
2. **Type Hierarchy** — understanding Scala's complex inheritance chains
3. **Execute Command** — triggering IntelliJ actions like organize imports

All three follow the existing provider pattern: one provider class per feature, `ReadAction`/`WriteCommandAction` wrappers, reflection-based Scala type detection where needed.

## Feature 1: Rename

### LSP Methods
- `textDocument/prepareRename` — validate rename is possible, return symbol name + range
- `textDocument/rename` — compute and return a `WorkspaceEdit` with all changes

### Implementation

**`RenameProvider` class:**
- `prepareRename(filePath, position)`: Find `PsiNamedElement` at position via `PsiUtils.findElementAtOffset`. Return the element's name and text range, or null if not renameable. Uses `ReadAction`.
- `rename(filePath, position, newName)`: In a `ReadAction`, find the `PsiNamedElement` at position, then use `ReferencesSearch.search()` (same API already used by `ReferencesProvider`) to find all usages across the project. Build a `WorkspaceEdit` containing `TextEdit` entries for each usage location plus the declaration itself, grouped by document URI. **No PSI mutation** — the client applies the edits.

**Scope:** Local variables, method names, class/trait/object names, parameters, type aliases. Cross-file renames are found by `ReferencesSearch` automatically.

**Threading:** Both `prepareRename` and `rename` use `ReadAction` only. No write actions needed — the server computes edits and returns them for the client to apply.

**Registration:** Add `RenameOptions` with `prepareProvider = true` to server capabilities via `capabilities.setRenameProvider(renameOptions)`.

## Feature 2: Type Hierarchy

### LSP Methods
- `typeHierarchy/prepareTypeHierarchy` — identify the type at position
- `typeHierarchy/supertypes` — return direct supertypes
- `typeHierarchy/subtypes` — return direct subtypes/implementors

### Implementation

**`TypeHierarchyProvider` class:**
- `prepare(filePath, position)`: Find class/trait/object at position. Return a `TypeHierarchyItem` with name, symbol kind, URI, range, selection range, and a `data` field encoding URI + position for later resolution (following the same pattern as `CallHierarchyProvider.findElementFromItem`).
- `supertypes(item)`: Resolve the PSI element from the item's `data` field. For Scala types, use reflection to access the extends block / template parents (e.g., reflective access to `getExtendsBlock` or `getSupers` on the underlying `PsiClass`). For Java PSI classes that Scala types may extend, use `PsiClass.getSupers()` directly. Return list of `TypeHierarchyItem`.
- `subtypes(item)`: Use `DefinitionsScopedSearch` (same approach as `ImplementationProvider`) to find direct subtypes/implementors. Return list of `TypeHierarchyItem`.

**Scala specifics:** Reflection-based detection for `ScClass`, `ScTrait`, `ScObject` following the existing `className.contains(...)` pattern. For supertype resolution, try reflection on `getSupers()` first (available on `PsiClass` which Scala PSI classes implement), then fall back to extends block inspection. Handles Scala's mixin linearization by reporting all direct supertypes.

**Threading:** All methods use `ReadAction`.

**Registration:** Add `typeHierarchyProvider: true` to server capabilities.

## Feature 3: Execute Command

### LSP Methods
- `workspace/executeCommand` — dispatch named commands with arguments

### Commands
- `scala.organizeImports` — optimize imports for a file (remove unused, sort)
- `scala.reformat` — reformat a file using IntelliJ's code style

### Implementation

**Command dispatch in `ScalaWorkspaceService`:**
- `executeCommand(command, arguments)`: Route by command name to the appropriate handler.
- Arguments: `JsonArray` where the first element is the file URI.
- **Threading:** Both commands require EDT + write action. Use `ApplicationManager.getApplication.invokeAndWait { WriteCommandAction.runWriteCommandAction { ... } }` following the same pattern as `DocumentSyncManager`.
- `scala.organizeImports`: Invoke `OptimizeImportsProcessor` on the PsiFile. Also wire as the command behind code actions with `kind: source.organizeImports` (connecting to existing `CodeActionProvider` categorization).
- `scala.reformat`: Invoke `CodeStyleManager.reformat()` on the PsiFile.
- **Client sync:** After server-side mutation, the document content has changed. Use `workspace/applyEdit` to send the resulting changes back to the client so it stays in sync, or capture the document state before/after and compute a diff to return.

**Error handling:** Return error response for unknown commands or missing/invalid arguments.

**Registration:** Add `executeCommandProvider` with `commands: ["scala.organizeImports", "scala.reformat"]` to server capabilities.

## Testing

### Integration Tests (BasePlatformTestCase)

**`RenameProviderIntegrationTest`:**
- Rename local variable — verify all usages in returned WorkspaceEdit
- Rename method — verify call sites in returned WorkspaceEdit
- Rename class — verify cross-file references in returned WorkspaceEdit
- Prepare rename on non-renameable element — returns null
- Rename with name conflict — verify graceful handling

**`TypeHierarchyProviderIntegrationTest`:**
- Prepare on class/trait — returns correct item with data field
- Supertypes of class extending trait — returns parent chain
- Subtypes of trait — returns implementors
- Prepare on non-type element — returns null

**`ExecuteCommandIntegrationTest`:**
- Organize imports — removes unused import
- Reformat — applies code style
- Unknown command — returns error

### Unit Tests
- Rename: edge cases (keywords, built-in symbols, name conflicts)
- Type hierarchy: no supers (implicit `Any`), multiple trait mixins
- Execute command: argument validation

## Files to Create/Modify

### New Files
- `lsp-server/src/intellij/RenameProvider.scala`
- `lsp-server/src/intellij/TypeHierarchyProvider.scala`
- `lsp-server/test/src/integration/RenameProviderIntegrationTest.scala`
- `lsp-server/test/src/integration/TypeHierarchyProviderIntegrationTest.scala`
- `lsp-server/test/src/integration/ExecuteCommandIntegrationTest.scala`

### Modified Files
- `lsp-server/src/ScalaLspServer.scala` — register new capabilities
- `lsp-server/src/ScalaTextDocumentService.scala` — add rename + type hierarchy handlers
- `lsp-server/src/ScalaWorkspaceService.scala` — add executeCommand handler
