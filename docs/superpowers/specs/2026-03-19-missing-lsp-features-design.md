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
- `prepareRename(filePath, position)`: Find `PsiNamedElement` at position via `PsiUtils.findElementAtOffset`. Return the element's name and text range, or null if not renameable.
- `rename(filePath, position, newName)`: Use IntelliJ's `RenameProcessor` / `RefactoringFactory` to compute all renames across the project. Collect changed files and their text edits. Return a `WorkspaceEdit` with `TextEdit` entries grouped by document URI.

**Scope:** Local variables, method names, class/trait/object names, parameters, type aliases. Cross-file renames handled automatically by IntelliJ's rename infrastructure.

**Threading:** `prepareRename` uses `ReadAction`. `rename` uses `WriteCommandAction` since it mutates PSI.

**Registration:** Add `renameProvider` with `prepareProvider: true` to server capabilities in `ScalaLspServer`.

## Feature 2: Type Hierarchy

### LSP Methods
- `typeHierarchy/prepareTypeHierarchy` — identify the type at position
- `typeHierarchy/supertypes` — return direct supertypes
- `typeHierarchy/subtypes` — return direct subtypes/implementors

### Implementation

**`TypeHierarchyProvider` class:**
- `prepare(filePath, position)`: Find class/trait/object at position. Return a `TypeHierarchyItem` with name, symbol kind, URI, range, and selection range.
- `supertypes(item)`: Resolve the PSI element from the item, walk `getSupers()` or equivalent to collect direct supertypes (extended class, mixed-in traits). Return list of `TypeHierarchyItem`.
- `subtypes(item)`: Use `DefinitionsScopedSearch` (same approach as `ImplementationProvider`) to find direct subtypes/implementors. Return list of `TypeHierarchyItem`.

**Scala specifics:** Reflection-based detection for `ScClass`, `ScTrait`, `ScObject` following the existing `className.contains(...)` pattern. Handles Scala's mixin linearization by reporting all direct supertypes.

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
- Both commands use `WriteCommandAction` to apply changes to the PSI/document.
- `scala.organizeImports`: Invoke `OptimizeImportsProcessor` on the PsiFile.
- `scala.reformat`: Invoke `CodeStyleManager.reformat()` on the PsiFile.
- Return null on success (edits applied directly to the document; diagnostics will re-publish).

**Error handling:** Return error response for unknown commands or missing/invalid arguments.

**Registration:** Add `executeCommandProvider` with `commands: ["scala.organizeImports", "scala.reformat"]` to server capabilities.

## Testing

### Integration Tests (BasePlatformTestCase)

**`RenameProviderIntegrationTest`:**
- Rename local variable — verify all usages updated
- Rename method — verify call sites updated
- Rename class — verify cross-file references updated
- Prepare rename on non-renameable element — returns null

**`TypeHierarchyProviderIntegrationTest`:**
- Prepare on class/trait — returns correct item
- Supertypes of class extending trait — returns parent chain
- Subtypes of trait — returns implementors
- Prepare on non-type element — returns null

**`ExecuteCommandIntegrationTest`:**
- Organize imports — removes unused import
- Reformat — applies code style
- Unknown command — returns error

### Unit Tests
- Rename: edge cases (keywords, built-in symbols)
- Type hierarchy: no supers (implicit `Any`), multiple trait mixins
- Execute command: argument validation

## Files to Create/Modify

### New Files
- `lsp-server/src/intellij/RenameProvider.scala`
- `lsp-server/src/intellij/TypeHierarchyProvider.scala`
- `lsp-server/test/integration/RenameProviderIntegrationTest.scala`
- `lsp-server/test/integration/TypeHierarchyProviderIntegrationTest.scala`
- `lsp-server/test/integration/ExecuteCommandIntegrationTest.scala`

### Modified Files
- `lsp-server/src/ScalaLspServer.scala` — register new capabilities
- `lsp-server/src/ScalaTextDocumentService.scala` — add rename + type hierarchy handlers
- `lsp-server/src/ScalaWorkspaceService.scala` — add executeCommand handler
