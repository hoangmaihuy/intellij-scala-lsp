# Design: Find Usages & Search Everywhere — Full IntelliJ Parity

**Date:** 2026-03-22
**Status:** Approved

## Problem

The LSP server's `textDocument/references` and `workspace/symbol` implementations have gaps compared to IntelliJ IDEA's native Find Usages and Search Everywhere features. This causes missed references, poor symbol ranking, and duplicate results.

## Scope

### References (textDocument/references)

1. **Multi-resolve for overloaded symbols** — Currently `resolveToDeclaration` uses `ref.resolve()` returning a single element. Add a new method `resolveToDeclarations` (plural) that returns `Seq[PsiElement]`: for `PsiPolyVariantReference`, call `multiResolve(false)` and collect all valid `ResolveResult.getElement` values; for regular references, fall back to `resolve()`. The existing `resolveToDeclaration` method (singular) is preserved unchanged for other callers (`ImplementationProvider`, `CallHierarchyProvider`).

2. **Expand search scope** — Change `GlobalSearchScope.projectScope(project)` to `GlobalSearchScope.allScope(project)` to include library references, matching IntelliJ's behavior. Note: for very common symbol names this may be slower. Mitigate by keeping a result limit (e.g., 500 references) and logging a warning if the limit is hit.

3. **Text occurrence search (Phase 2)** — After structural search, scan non-Scala files using `PsiSearchHelper.getInstance(project).processUsagesInNonJavaFiles(element, name, processor, scope)`. The processor receives `(PsiFile, startOffset, endOffset)` triples — for each, compute the Location manually using `PsiUtils.offsetToRange(file.getText, startOffset, endOffset)` and the file's URI. Collect these into a set of text occurrence locations, then subtract any locations already found by structural search. Only non-code file types are searched (configs, build files, XML, etc.).

4. **ScalaFindUsagesHandler integration** — Use the Scala plugin's handler factory via reflection to discover secondary elements. Reflection details:
   - Factory class: `org.jetbrains.plugins.scala.findUsages.factory.ScalaFindUsagesHandlerFactory`
   - Get instance: iterate `FindUsagesHandlerFactory.EP_NAME.getExtensionList(project)` to find the Scala factory by class name
   - Create handler: `factory.createFindUsagesHandler(element, false)` returns a `FindUsagesHandler`
   - Get secondary elements: `handler.getSecondaryElements()` returns `Array[PsiElement]`

   Secondary elements include:
   - Companion objects/classes (`fakeCompanionClass`)
   - Bean property getters/setters (`PsiMethodWrapper`)
   - Synthetic apply/unapply methods' containing objects

   Search references for all secondary elements alongside the primary target. If reflection fails (class not found, method changed), log and fall back to primary-only search.

5. **Usage type categorization** — Classify each reference by walking up the PSI tree from `ref.getElement`:
   - **Import** — ancestor is `PsiImportStatement` or element class name contains `ScImportExpr`
   - **Write** — ancestor is `ScAssignment` (LHS) or `ScVariableDefinition`, or `WriteAccessDetector` reports write access
   - **Type reference** — ancestor class name contains `ScSimpleTypeElement`, `ScParameterizedTypeElement`, or element is in extends/with clause
   - **Pattern** — ancestor class name contains `ScPattern`, `ScCaseClause`
   - **Text occurrence** — produced by Phase 2 text search (no PSI classification needed)
   - **Read** — default for all other code references

   Detection uses class name string matching (via `getClass.getName.contains`) to avoid classloader issues with Scala plugin types.

### Workspace Symbols (workspace/symbol)

6. **Relevance ranking** — Score results by match quality:
   1. Exact match (case-sensitive)
   2. Exact case-insensitive
   3. Prefix match
   4. Camel-case match (e.g., "GFS" → "GlobalFileSystem")
   5. Substring match

   Within each tier, project symbols rank above library symbols. Use IntelliJ's `com.intellij.psi.codeStyle.MinusculeMatcher` (via `NameUtil.buildMatcher(query).build()`) for camel-case matching. If unavailable via classloader, fall back to a simple initial-letter check.

7. **Companion object dedup at LSP layer** — When adding a result with qualKey `pkg.Foo`, also check for `pkg.Foo$` (and vice versa). Prefer the class over the companion object. This removes the need for MCP-layer companion dedup.

8. **Concurrent contributor iteration** — Process contributors sequentially within a single `smartReadAction` (IntelliJ's read-action model does not support nested parallelism). However, optimize by: (a) collecting all matching names from all contributors first in a single pass, (b) batching `processElementsWithName` calls, and (c) using `ConcurrentHashMap` for the `seen` set to enable safe concurrent access if future optimization allows separate read actions per contributor.

9. **Enhanced dedup (SEResultsEqualityProvider-style)** — Go beyond qualified name dedup:
   - Same FQN, different source (cached JAR vs decompiled) — resolve to canonical location
   - Wrapper elements (PsiClassWrapper, PsiMethodWrapper) — unwrap before computing qualKey
   - Inner classes — include outer class in qualKey

## Data Flow

### References — New Flow

```
resolveToDeclarations (new method) → Seq[PsiElement] via multiResolve
  ↓
For cached source files: resolveLibraryElement for each
  ↓
getSecondaryElements via ScalaFindUsagesHandlerFactory (reflection)
  ↓
For each (primary + secondary):
  ReferencesSearch.search(element, allScope(project))
  ↓
Phase 2: PsiSearchHelper.processUsagesInNonJavaFiles(name, scope)
  Filter out overlapping structural refs
  ↓
Classify each reference → ReferenceResult(location, usageType)
  ↓
Deduplicate by Location
  ↓
Return Seq[Location] for LSP (textDocument/references)
Store Seq[ReferenceResult] in ReferencesProvider.lastResults for MCP access
MCP calls custom method referencesProvider.getLastResultsWithTypes() after LSP call
```

### Workspace Symbols — New Flow

```
Query → extract simpleName + fqnPrefix
  ↓
Collect contributors (CLASS_EP + SYMBOL_EP)
  ↓
Sequential (within smartReadAction): For each contributor, processNames → matching names
  ↓
Sequential: For each matching name, processElementsWithName
  ↓
Unwrap synthetic elements (PsiClassWrapper, PsiMethodWrapper)
  ↓
Compute qualKey with companion dedup (prefer non-$ class)
  ↓
Score relevance: exact > case-insensitive > prefix > camelCase > substring
  ↓
Sort by (relevance tier, project-vs-library, name)
  ↓
Return Seq[SymbolInformation]
```

## File Changes

### LSP Server (Scala)

| File | Change |
|---|---|
| `ReferencesProvider.scala` | Major rewrite: multi-resolve, allScope, text occurrences, secondary elements, usage types |
| `SymbolProvider.scala` | Add relevance ranking, companion dedup, concurrent contributors, enhanced dedup |
| `PsiUtils.scala` | Add `resolveToDeclarations` (new, returns `Seq[PsiElement]`), `getUsageType`, `unwrapSyntheticElement`. Keep existing `resolveToDeclaration` unchanged. |
| `ReferenceResult.scala` | **New** — `case class ReferenceResult(location: Location, usageType: String)` |

### MCP Server (TypeScript)

| File | Change |
|---|---|
| `navigation.ts` | `references` tool uses usage types for grouping in output presentation. Remove companion filtering from `resolveTargets` (now server-side). Usage types retrieved via custom LSP request `scala/referencesWithTypes` that returns `{location, usageType}[]`. |
| `symbol-resolver.ts` | Simplify — companion dedup now handled server-side. Remove companion match quality logic. |

## Backward Compatibility

- `textDocument/references` continues returning `List[Location]` per LSP spec
- `workspace/symbol` continues returning `List[SymbolInformation]` per LSP spec
- Usage types exposed via a new custom LSP request `scala/referencesWithTypes` that the MCP layer calls after the standard references call. Returns `Array[{location: Location, usageType: String}]`. Standard LSP clients ignore the custom method.
- MCP tool signatures unchanged — only output formatting changes (grouped by usage type)

## Error Handling

- `ScalaFindUsagesHandlerFactory` accessed via reflection (classloader isolation in daemon mode) — catch and fall back to current behavior if factory unavailable
- Text occurrence search — catch exceptions, log, return structural results only
- Concurrent contributor failures — isolate per-contributor, collect partial results
- `multiResolve` failure — fall back to single `resolve()` result

## Not In Scope

- **Implicit references / for-comprehension desugaring** — Requires compiler bytecode indices (`CompilerIndicesReferencesSearch`), not available in daemon mode
- **Custom LSP protocol extensions** — Usage types conveyed through MCP presentation only
- **Find Usages dialog/options UI** — LSP has no equivalent; we use IntelliJ defaults
