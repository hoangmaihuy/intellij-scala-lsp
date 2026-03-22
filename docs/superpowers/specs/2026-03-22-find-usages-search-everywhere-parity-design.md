# Design: Find Usages & Search Everywhere — Full IntelliJ Parity

**Date:** 2026-03-22
**Status:** Approved

## Problem

The LSP server's `textDocument/references` and `workspace/symbol` implementations have gaps compared to IntelliJ IDEA's native Find Usages and Search Everywhere features. This causes missed references, poor symbol ranking, and duplicate results.

## Scope

### References (textDocument/references)

1. **Multi-resolve for overloaded symbols** — Currently `resolveToDeclaration` uses `ref.resolve()` returning a single element. For `PsiPolyVariantReference`, use `multiResolve(false)` to get all possible targets and search references for each.

2. **Expand search scope** — Change `GlobalSearchScope.projectScope(project)` to `GlobalSearchScope.allScope(project)` to include library references, matching IntelliJ's behavior.

3. **Text occurrence search (Phase 2)** — After structural search, scan non-Scala files using `PsiSearchHelper.processUsagesInNonJavaFiles()` for text occurrences of the element's name. Filter out matches overlapping with already-found structural references.

4. **ScalaFindUsagesHandler integration** — Use the Scala plugin's `ScalaFindUsagesHandlerFactory` (via reflection for classloader isolation) to discover secondary elements:
   - Companion objects/classes
   - Bean property getters/setters
   - Synthetic apply/unapply methods' containing objects

   Search references for all secondary elements alongside the primary target.

5. **Usage type categorization** — Classify each reference:
   - **Import** — inside `ScImportExpr` or `PsiImportStatement`
   - **Write** — LHS of assignment or var definition
   - **Read** — default for other code references
   - **Type reference** — in type position (extends clause, type annotation)
   - **Pattern** — in pattern match context
   - **Text occurrence** — non-code usage from Phase 2

### Workspace Symbols (workspace/symbol)

6. **Relevance ranking** — Score results by match quality:
   1. Exact match (case-sensitive)
   2. Exact case-insensitive
   3. Prefix match
   4. Camel-case match (e.g., "GFS" → "GlobalFileSystem")
   5. Substring match

   Within each tier, project symbols rank above library symbols.

7. **Companion object dedup at LSP layer** — When adding a result with qualKey `pkg.Foo`, also check for `pkg.Foo$` (and vice versa). Prefer the class over the companion object. This removes the need for MCP-layer companion dedup.

8. **Concurrent contributor iteration** — Parallelize contributor processing within the `smartReadAction` using `java.util.concurrent` primitives. Use `ConcurrentHashMap` for the `seen` set instead of mutable `Set`.

9. **Enhanced dedup (SEResultsEqualityProvider-style)** — Go beyond qualified name dedup:
   - Same FQN, different source (cached JAR vs decompiled) — resolve to canonical location
   - Wrapper elements (PsiClassWrapper, PsiMethodWrapper) — unwrap before computing qualKey
   - Inner classes — include outer class in qualKey

## Data Flow

### References — New Flow

```
resolveToDeclaration → Seq[PsiElement] via multiResolve
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
Return Seq[Location] for LSP, Seq[ReferenceResult] internally for MCP
```

### Workspace Symbols — New Flow

```
Query → extract simpleName + fqnPrefix
  ↓
Collect contributors (CLASS_EP + SYMBOL_EP)
  ↓
Parallel: For each contributor, processNames → matching names
  ↓
Parallel: For each matching name, processElementsWithName
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
| `PsiUtils.scala` | `resolveToDeclaration` returns `Seq[PsiElement]`, add `getUsageType`, add `unwrapSyntheticElement` |
| `ReferenceResult.scala` | **New** — `case class ReferenceResult(location: Location, usageType: String)` |

### MCP Server (TypeScript)

| File | Change |
|---|---|
| `navigation.ts` | `references` tool uses usage types for grouping in output presentation |
| `symbol-resolver.ts` | Simplify — companion dedup now handled server-side |

## Backward Compatibility

- `textDocument/references` continues returning `List[Location]` per LSP spec
- `workspace/symbol` continues returning `List[SymbolInformation]` per LSP spec
- Usage types are internal enrichment, exposed only through MCP presentation
- MCP tool signatures unchanged

## Error Handling

- `ScalaFindUsagesHandlerFactory` accessed via reflection (classloader isolation in daemon mode) — catch and fall back to current behavior if factory unavailable
- Text occurrence search — catch exceptions, log, return structural results only
- Concurrent contributor failures — isolate per-contributor, collect partial results
- `multiResolve` failure — fall back to single `resolve()` result

## Not In Scope

- **Implicit references / for-comprehension desugaring** — Requires compiler bytecode indices (`CompilerIndicesReferencesSearch`), not available in daemon mode
- **Custom LSP protocol extensions** — Usage types conveyed through MCP presentation only
- **Find Usages dialog/options UI** — LSP has no equivalent; we use IntelliJ defaults
