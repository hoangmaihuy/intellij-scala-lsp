# LSP Provider Delegation Audit

Review of each LSP provider to identify ad-hoc reimplementations that could be delegated to IntelliJ or the Scala plugin.

## Summary

| Provider | Status | Delegation Opportunity |
|---|---|---|
| DefinitionProvider | Good | Already uses `GotoDeclarationHandler` EP |
| ReferencesProvider | Good | Already uses `ReferencesSearch` |
| TypeDefinitionProvider | Good | Already uses `TypeDeclarationProvider` EP |
| ImplementationProvider | Good | Already uses `DefinitionsScopedSearch` |
| CompletionProvider | Good | Already uses `CompletionContributor` EP |
| RenameProvider | Good | Already uses `RenamePsiElementProcessorBase` |
| FoldingRangeProvider | Good | Already uses `LanguageFolding` EP |
| FormattingProvider | Good | Already uses `CodeStyleManager` |
| DiagnosticsProvider | Good | Already uses `DaemonCodeAnalyzer` |
| CodeActionProvider | Good | Already uses `DaemonCodeAnalyzer` + `IntentionManager` |
| SymbolProvider | Good | `workspace/symbol` delegates to `DefaultChooseByNameItemProvider` |
| HoverProvider | Good | Already uses `LanguageDocumentation` EP |
| DocumentHighlightProvider | Investigate | Could use `HighlightUsagesHandlerFactory` EP |
| SignatureHelpProvider | **Delegate** | Should use `ParameterInfoHandler` EP (3 Scala handlers exist) |
| SelectionRangeProvider | Investigate | Could use `ExtendWordSelectionHandler` EP |
| InlayHintProvider | Investigate | Scala plugin has custom hint passes, but uses internal API |
| SemanticTokensProvider | Investigate | Could use `HighlightVisitor` / `Annotator` pipeline |
| CallHierarchyProvider | Good | Already uses `ReferencesSearch` + `DefinitionsScopedSearch` (HierarchyProvider EP is UI-oriented) |
| TypeHierarchyProvider | Investigate | IntelliJ has `HierarchyProvider` EP |
| DocumentLinkProvider | Low priority | Regex-based, no standard EP |
| OnTypeFormattingProvider | Investigate | Could use `TypedHandlerDelegate` / `EnterHandlerDelegate` |
| SuperMethodCodeLens | N/A | No Scala plugin equivalent |
| CodeLensProvider | N/A | Registry pattern, fine as-is |

## Detailed Analysis

### 1. SignatureHelpProvider — DELEGATE to ParameterInfoHandler

**Current**: Manual parenthesis matching by scanning backwards (lines 50-67), then reflection to extract `ScFunction.effectiveParameterClauses()`, with fallbacks to Java `PsiMethod` and `NavigationItem`.

**IntelliJ Scala plugin provides**: `ScalaFunctionParameterInfoHandler`, `ScalaTypeParameterInfoHandler`, `ScalaPatternParameterInfoHandler` — all implementing `ParameterInfoHandlerWithTabActionSupport`. These handle:
- Finding the argument list element at cursor
- Resolving the called method
- Extracting parameter clauses with types
- Rendering parameter info with active parameter highlighting

**Delegation approach**: Use `ParameterInfoHandler.EP_NAME.forLanguage(ScalaLanguage)` to get the registered handlers, then call `findElementForParameterInfo()` + `updateParameterInfo()`. This eliminates all the manual parenthesis scanning and reflection.

---

### 2. DocumentHighlightProvider — DELEGATE to HighlightUsagesHandler

**Current**: Simple `ReferencesSearch` for local scope with Write/Read classification (64 lines).

**IntelliJ Scala plugin provides**: `ScalaHighlightUsagesHandlerFactory` with 7 specialized handlers:
- `ScalaHighlightImplicitUsagesHandler` — implicit parameter usages
- `ScalaHighlightExprResultHandler` — expression result types
- `ScalaHighlightExitPointsHandler` — return/exit points
- `ScalaHighlightConstructorInvocationUsages` — constructor invocations
- `CompanionHighlightHandler` — companion object/class
- `ScalaHighlightCaseClassHandler` — case class apply/unapply
- `ScHighlightEndMarkerUsagesHandler` — end markers

**Delegation approach**: Use `HighlightUsagesHandlerFactory.EP_NAME` to get registered factories, call `createHighlightUsagesHandler()`. Falls back to default if no Scala-specific handler applies. This gives us richer highlighting (e.g., implicit usages, exit points) for free.

---

### 3. SelectionRangeProvider — Investigate ExtendWordSelectionHandler

**Current**: Manual PSI tree walk-up collecting progressively larger ranges (67 lines).

**IntelliJ Scala plugin provides**: 8 selection handlers extending `ExtendWordSelectionHandlerBase`:
- `ScalaWordSelectioner` — parameter clauses, extends blocks, qualified references
- `ScalaStringLiteralSelectioner` — string literals
- `ScalaStatementGroupSelectioner` — statement groups
- `ScalaCodeBlockSelectioner` — code blocks
- `ScalaDocCommentSelectioner` — ScalaDoc
- Others for semicolons, attributes

**Delegation approach**: The `ExtendWordSelectionHandler` EP produces selection ranges for a given element. We could iterate through registered handlers for each PSI element in the walk-up chain. However, the current approach (simple PSI parent walk) may actually produce better LSP-compatible results since `selectionRange` expects nested ranges from narrow to wide, which is exactly what walking PSI parents gives. **Worth investigating but may not be a clear win.**

---

### 4. InlayHintProvider — Investigate but may not be feasible

**Current**: Heavy reflection chains to call `.type()`, `.returnType()`, `.findImplicitArguments()`, `.implicitConversion()` on Scala PSI elements (332 lines).

**IntelliJ Scala plugin provides**: Custom hint system via `ScalaTypeHintsPass` and `ScalaMethodChainInlayHintsPass` traits. These use the plugin's internal `Hint` class, not the standard `InlayHintsProvider` API.

**Challenge**: The Scala plugin's hint passes are tightly coupled to the editor rendering pipeline. They produce `Hint` objects for the IntelliJ editor, not structured data we can easily convert to LSP `InlayHint`. The reflection approach in our provider, while ugly, directly accesses the same type information the plugin uses internally.

**Verdict**: Keep current approach unless we can find a way to invoke the hint passes and extract their `Hint` objects. The reflection is necessary due to the classloader boundary.

---

### 5. SemanticTokensProvider — Investigate HighlightVisitor pipeline

**Current**: 586 lines of manual token classification via PSI type checking, binding/field classification, string escape splitting, and Metals-compatible token type mapping.

**IntelliJ Scala plugin provides three highlighting systems**:
1. `ScalaSyntaxHighlighter` — lexical (token-based) via `SyntaxHighlighterBase`
2. `ScalaColorSchemeAnnotator` — PSI-based via `Annotator`
3. `ScalaSyntaxHighlightingVisitor` — PSI-based via `HighlightVisitor` (faster)

**Delegation approach**: Run the `HighlightVisitor` and `Annotator` pipelines on a file to collect `HighlightInfo` objects with `TextAttributesKey` data. Map IntelliJ's `TextAttributesKey` names to LSP semantic token types. This would give us Scala-specific highlighting (named arguments, annotations, soft keywords) for free.

**Challenge**: The mapping from IntelliJ's `TextAttributesKey` → LSP `SemanticTokenType` is non-trivial. IntelliJ uses a much richer color scheme (dozens of keys) vs LSP's 23 standard token types. We'd need a mapping table. Also, `HighlightVisitor.analyze()` requires a `HighlightInfoHolder` which is an internal class.

**Verdict**: Worth investigating. Even a partial delegation would reduce the 586-line provider significantly.

---

### 6. CallHierarchyProvider — KEEP (already uses correct APIs)

**Current**: 217 lines using `ReferencesSearch` + `DefinitionsScopedSearch` with manual tree walking for outgoing references, reflection for case class synthetic methods.

**IntelliJ provides**: `HierarchyProvider` EP (`com.intellij.callHierarchyProvider`) with `CallHierarchyBrowserBase`. **However**, this EP is UI-oriented — it creates `HierarchyBrowser` objects with `JTree` views, not a data-extraction API.

**Scala plugin provides**: `ScalaCallHierarchyProvider` (extends `JavaCallHierarchyProvider`) with `ScalaCallHierarchyBrowser`.

**Verdict**: The current implementation already uses correct IntelliJ APIs for core search (`ReferencesSearch`, `DefinitionsScopedSearch`, `PsiMethod.findSuperMethods()`). The only ad-hoc part is case class synthetic methods (15 lines of reflection). Delegating to the UI-oriented `HierarchyProvider` EP would be more complex, not simpler.

---

### 7. TypeHierarchyProvider — Investigate HierarchyProvider EP

**Current**: 161 lines using `DefinitionsScopedSearch` for subtypes, reflection on `.extendsBlock()` → `.templateParents()` for supertypes, hardcoded synthetic type filtering.

**IntelliJ provides**: `TypeHierarchyProvider` EP (`com.intellij.typeHierarchyProvider`) with `TypeHierarchyBrowserBase` managing three views: full type hierarchy, supertypes-only, subtypes-only.

**Challenge**: The `HierarchyProvider` API is UI-oriented — it creates a `HierarchyBrowser` for display in a tool window. Extracting just the hierarchy data (supertypes/subtypes list) requires working with `HierarchyTreeStructure` which is tightly coupled to the browser UI. Our current approach using `DefinitionsScopedSearch` for subtypes is actually the same underlying API the hierarchy browser uses internally.

**Verdict**: The supertypes reflection (`extendsBlock()` → `templateParents()`) is the main ad-hoc part. The subtypes search via `DefinitionsScopedSearch` is already using IntelliJ's core API correctly. Worth delegating the supertypes part if we can find a cleaner way to resolve parent types.

---

### 8. OnTypeFormattingProvider — Investigate TypedHandlerDelegate

**Current**: 187 lines using `CodeStyleManager` with copy-based formatting for newline/brace triggers, manual triple-quote detection.

**IntelliJ provides**: `TypedHandlerDelegate` and `EnterHandlerDelegate` EPs that handle auto-indent, brace matching, and character-specific behavior.

**Challenge**: These delegates modify the editor directly (imperative), while LSP needs a list of text edits (declarative). The current copy-based approach works around this limitation. Delegating to `TypedHandlerDelegate` would require intercepting the edits it applies to the copy.

**Verdict**: Current approach is reasonable given the LSP constraint. Low priority for change.

---

### 9. HoverProvider — Minor improvement possible

**Current**: Uses `LanguageDocumentation.INSTANCE.forLanguage()` correctly, but has a custom HTML-to-Markdown regex converter (lines 81-133).

**Potential improvement**: The HTML→Markdown conversion is ad-hoc regex. Could use a library like flexmark-java's HTML-to-Markdown converter. However, the current regex handles the common cases from IntelliJ's doc output well enough.

**Verdict**: Low priority. The ad-hoc regex converter works for IntelliJ's limited HTML output.

---

## PsiUtils.scala — Cross-cutting utilities

Several utilities in PsiUtils are used across providers:

1. **Offset ↔ Position conversion**: Standard LSP conversion, no IntelliJ API for this
2. **JAR source caching**: Custom but necessary — IntelliJ doesn't expose source JAR content as file:// URIs
3. **Synthetic element unwrapping**: Reflection-based, necessary due to classloader boundary
4. **Usage type classification**: Could potentially use `UsageTypeProvider` EP instead of manual checks

## ScalaTypes.scala — Reflection bridge

380 lines of reflection-based access to Scala plugin types. This is inherently ad-hoc but **necessary** due to the classloader separation between our plugin and the Scala plugin. No delegation opportunity here — this IS the bridge layer.

## Recommended Priority

1. **SignatureHelpProvider** → `ParameterInfoHandler` EP (high value, eliminates most complex reflection)
2. **DocumentHighlightProvider** → `HighlightUsagesHandlerFactory` EP (medium value, gains Scala-specific highlighting)
3. **SemanticTokensProvider** → `HighlightVisitor` pipeline (high value but high effort)
4. **TypeHierarchyProvider** → supertypes resolution only (low-medium value, subtypes already correct)
5. **SelectionRangeProvider** → `ExtendWordSelectionHandler` EP (low value, current approach is clean)
6. ~~**CallHierarchyProvider**~~ → Skipped: already uses correct APIs; `HierarchyProvider` EP is UI-oriented

## IntelliJ API Reference

Extension points and key classes for delegation:

| Feature | EP Name | Key Class | Scala Plugin Implementation |
|---|---|---|---|
| Parameter Info | `com.intellij.parameterInfoHandler` | `ParameterInfoHandler` | `ScalaFunctionParameterInfoHandler`, `ScalaTypeParameterInfoHandler`, `ScalaPatternParameterInfoHandler` |
| Highlight Usages | `com.intellij.highlightUsagesHandlerFactory` | `HighlightUsagesHandlerFactory` | `ScalaHighlightUsagesHandlerFactory` (7 specialized handlers) |
| Call Hierarchy | `com.intellij.callHierarchyProvider` | `HierarchyProvider` | `ScalaCallHierarchyProvider` → `ScalaCallHierarchyBrowser` |
| Type Hierarchy | `com.intellij.typeHierarchyProvider` | `HierarchyProvider` | (extends Java provider) |
| Structure View | `com.intellij.lang.psiStructureViewFactory` | `PsiStructureViewFactory` | `ScalaStructureViewFactory` → `ScalaStructureViewModel` |
| Extend Selection | `com.intellij.extendWordSelectionHandler` | `ExtendWordSelectionHandlerBase` | `ScalaWordSelectioner` + 7 others |
| Highlight Visitor | `com.intellij.highlightVisitor` | `HighlightVisitor` | `ScalaSyntaxHighlightingVisitor`, `ScalaColorSchemeAnnotator` |
| Inlay Hints | `com.intellij.codeInsight.inlayProvider` | `InlayHintsProvider` | Custom `ScalaTypeHintsPass` (non-standard API) |
| Typed Handler | `com.intellij.typedHandler` | `TypedHandlerDelegate` | (Scala handlers exist) |
| Enter Handler | `com.intellij.enterHandlerDelegate` | `EnterHandlerDelegate` | (Scala handlers exist) |
