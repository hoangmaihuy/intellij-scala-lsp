# Implementation Details

## LSP Provider Architecture

Each LSP method is handled by a dedicated provider class. All providers follow the same pattern:

```scala
class XxxProvider(projectManager: IntellijProjectManager):
  def doSomething(uri: String, position: Position): Result =
    projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        // delegate to IntelliJ API
```

### Provider → IntelliJ API Mapping

#### Navigation

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `DefinitionProvider` | `textDocument/definition` | `PsiReference.resolve()`, `PsiPolyVariantReference.multiResolve()` | Standard PSI resolution; `DefinitionOrReferencesProvider` wraps this with a fallback to references when definition resolves to itself |
| `TypeDefinitionProvider` | `textDocument/typeDefinition` | `TypeDeclarationProvider.EP_NAME` extension point | Iterates registered handlers including Scala plugin's |
| `ImplementationProvider` | `textDocument/implementation` | `DefinitionsScopedSearch.search()` | Scoped to `GlobalSearchScope.projectScope` |
| `ReferencesProvider` | `textDocument/references` | `ReferencesSearch.search()` | Includes declaration if `context.includeDeclaration` is true |
| `SymbolProvider` | `textDocument/documentSymbol` | PSI tree walk | Walks PSI children to collect classes, traits, methods, vals into hierarchical `DocumentSymbol` list |
| `SymbolProvider` | `workspace/symbol` | `ChooseByNameContributor` EP | Searches across all project files via registered contributors |
| `DocumentLinkProvider` | `textDocument/documentLink` | PSI reference walk | Creates clickable links to imported types |
| `DocumentHighlightProvider` | `textDocument/documentHighlight` | `ReferencesSearch.search()` | Finds references at position, returns highlight ranges |

#### Hierarchy

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `CallHierarchyProvider` | `callHierarchy/prepare` | PSI reference resolution | Returns `CallHierarchyItem` for the symbol at position |
| `CallHierarchyProvider` | `callHierarchy/incomingCalls` | `CallReferenceProcessor` | Finds all call sites of the target method |
| `CallHierarchyProvider` | `callHierarchy/outgoingCalls` | PSI subtree walk | Walks the method body for reference expressions to other methods |
| `TypeHierarchyProvider` | `typeHierarchy/prepare` | PSI hierarchy | Returns `TypeHierarchyItem` at position |
| `TypeHierarchyProvider` | `typeHierarchy/supertypes` | `PsiClass.getSupers()` | Standard PsiClass API — works for Scala types since `ScClass`/`ScTrait` implement `PsiClass` |
| `TypeHierarchyProvider` | `typeHierarchy/subtypes` | `DefinitionsScopedSearch` | Finds all subtypes within project scope |

#### Editing

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `CompletionProvider` | `textDocument/completion` | `CompletionContributor.forLanguage()` | Lazy resolve with 30s cache TTL; generates snippet insert text for methods with parameters |
| `CompletionProvider` | `completionItem/resolve` | `LanguageDocumentation` | Populates detail, documentation, and auto-import `additionalTextEdits` |
| `SignatureHelpProvider` | `textDocument/signatureHelp` | `ParameterInfoHandler` EP | Trigger chars: `(`, `,`; returns parameter info for method calls |
| `CodeActionProvider` | `textDocument/codeAction` | `IntentionManager.getInstance()`, `DaemonCodeAnalyzer` | Collects quick fixes from highlighting + intention actions; supports QuickFix, Refactor, Source, RefactorExtract, RefactorInline kinds |
| `CodeActionProvider` | `codeAction/resolve` | Intention action execution | Computes workspace edits by applying fix on PSI copy and diffing the result |
| `RenameProvider` | `textDocument/rename` | `RefactoringFactory`, `ReferencesSearch` | Supports `prepareRename` to validate and return the rename range |
| `FormattingProvider` | `textDocument/formatting` | `CodeStyleManager.reformat()` | Reformats entire document |
| `FormattingProvider` | `textDocument/rangeFormatting` | `CodeStyleManager.reformat()` | Reformats selected range |
| `OnTypeFormattingProvider` | `textDocument/onTypeFormatting` | `CodeStyleManager`, PSI edit tracking | Trigger chars: `\n`, `"`, `}`; handles indentation, triple-quote close, brace block reformat |

#### Display

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `HoverProvider` | `textDocument/hover` | `LanguageDocumentation.INSTANCE.forLanguage()` | Returns type info + ScalaDoc as markdown; converts IntelliJ's HTML output to clean markdown |
| `DiagnosticsProvider` | `textDocument/publishDiagnostics` | `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` | Push-based: registers a topic listener, publishes asynchronously when analysis completes |
| `InlayHintProvider` | `textDocument/inlayHint` | `PsiNameIdentifierOwner`, `NavigationItem.getPresentation()` | Type hints for vals, parameters, return types; resolve support for documentation |
| `SemanticTokensProvider` | `textDocument/semanticTokens/full` | PSI element classification via `ScalaTypes` | 16 token types (keyword, type, class, interface, enum, method, property, variable, parameter, typeParameter, string, number, comment, function, operator, regexp); 8 modifiers (declaration, static, abstract, readonly, modification, documentation, lazy, deprecated) |
| `SemanticTokensProvider` | `textDocument/semanticTokens/range` | PSI element classification via `ScalaTypes` | Same as full, filtered to range |
| `CodeLensProvider` | `textDocument/codeLens` | `SuperMethodCodeLens` | Shows "Overrides" annotation on methods that implement trait/class members |
| `FoldingRangeProvider` | `textDocument/foldingRange` | `LanguageFolding.INSTANCE.forLanguage()` | Uses `LanguageFolding.buildFoldingDescriptors()` |
| `SelectionRangeProvider` | `textDocument/selectionRange` | PSI tree walk (leaf to root) | No IntelliJ equivalent — LSP-specific feature |

#### Document Sync & Workspace

| Provider | LSP Method | IntelliJ API | Notes |
|---|---|---|---|
| `DocumentSyncManager` | `didOpen/didChange/didClose/didSave` | `FileDocumentManager`, `WriteCommandAction` | Full text sync; document edits on EDT under write lock |
| `ScalaWorkspaceService` | `workspace/executeCommand` | `OptimizeImportsProcessor`, `CodeStyleManager` | Three commands: `scala.organizeImports`, `scala.reformat`, `scala.gotoLocation` |
| `ScalaWorkspaceService` | `workspace/willRenameFiles` | PSI type definition walk | When a `.scala` file is renamed, finds type definitions matching the old filename and generates rename edits |
| `ScalaWorkspaceService` | `workspace/didChangeWatchedFiles` | `VfsUtil.markDirtyAndRefresh()` | Notifies IntelliJ VFS of external file changes |
| `ScalaWorkspaceService` | `workspace/didChangeWorkspaceFolders` | `IntellijProjectManager` | Opens/closes projects for added/removed workspace folders |

### Shared Utilities

**`PsiUtils`** provides shared helpers used by all providers:
- `positionToOffset` / `offsetToPosition` — LSP position ↔ IntelliJ offset conversion
- `elementToLocation` / `elementToRange` — PSI element → LSP Location/Range
- `findReferenceElementAt` — finds the reference-bearing element at an offset
- `resolveToDeclaration` — resolves an element at an offset to its declaration
- `getSymbolKind` — maps PSI class names to LSP `SymbolKind`
- `vfToUri` — VirtualFile → `file://` URI
- JAR source handling: for `.class` files in JARs, tries to find original source from source JARs, falls back to IntelliJ's decompiled PSI text, caches to `~/.cache/intellij-scala-lsp/sources/`

**`LspConversions`** converts between IntelliJ and LSP types:
- Severity mapping (IntelliJ `HighlightSeverity` → LSP `DiagnosticSeverity`)
- Symbol kind mapping
- Range/Position conversions

## Classloader Safety: ScalaTypes

IntelliJ loads the Scala plugin via `PluginClassLoader`, while our LSP server code runs on the boot classpath. This means `instanceof ScTypeDefinition` always fails — the class objects differ across classloaders even though the fully-qualified name is the same.

`ScalaTypes` solves this with runtime class name matching through the element's own classloader:

```scala
private def isInstanceOfScala(e: PsiElement, fqn: String): Boolean =
  loadClass(e.getClass.getClassLoader, fqn) match
    case Some(cls) => cls.isInstance(e)  // Works across classloaders
    case None => false
```

This provides 50+ type-safe predicates used throughout the providers:
- **Type definitions**: `isTypeDefinition`, `isClass`, `isTrait`, `isObject`, `isEnum`, `isTemplateDefinition`
- **Members**: `isFunction`, `isFunctionDefinition`, `isValue`, `isVariable`, `isTypeAlias`, `isGiven`
- **Parameters**: `isParameter`, `isTypeParam`, `isBindingPattern`, `isFieldId`
- **References**: `isReference`, `isReferenceExpression`, `isMethodCall`
- **Types**: `isSimpleTypeElement`, `isParameterizedTypeElement`
- **Expressions**: `isExpression`, `isImplicitArgumentsOwner`

Results are cached in a `ConcurrentHashMap[(ClassLoader, FQN)] → Option[Class[?]]` to avoid repeated `Class.forName` calls.

## JSON-RPC Wiring (Scala 3 Bridge Method Workaround)

`LspLauncher.java` wraps the Scala LSP server in Java delegate classes before handing it to lsp4j's `Launcher.Builder`. This is necessary because Scala 3 generates bridge methods that copy `@JsonNotification`/`@JsonRequest` annotations, causing lsp4j's reflection-based method scanning to find duplicate RPC method names.

The workaround:
1. `JavaLanguageServer` wraps `ScalaLspServer`
2. `JavaTextDocumentService` wraps `ScalaTextDocumentService`
3. `JavaWorkspaceService` wraps `ScalaWorkspaceService`
4. `getSupportedMethods()` is overridden to scan only the Java interfaces
5. `createRemoteEndpoint()` uses pre-computed methods from Java interfaces

## Bootstrap

The LSP server uses IntelliJ's production startup via `com.intellij.idea.Main`. The launcher passes `scala-lsp` as the first argument, which IntelliJ's `ApplicationStarter` extension point resolves to `ScalaLspApplicationStarter` after the full platform is initialized (plugin loading, kernel, services).

This production bootstrap:
- Properly handles IntelliJ Ultimate's classloader hierarchy (content modules like `intellij.rd.platform` get their own `PluginClassLoader`)
- Uses the standard extension point system — no test-framework dependency at runtime
- Supports both Community (IC) and Ultimate (IU) SDK installations

The `com.intellij.appStarter` extension point lookup happens in `ApplicationLoader.createAppStarter()`, which runs *after* `registerComponents()` — so plugin-provided starters are found correctly. The key is using `id="scala-lsp"` (not `commandName`) in plugin.xml, as `findStarter()` matches by `orderId`.

JVM flags (including `--add-opens`, `-Djava.system.class.loader=PathClassLoader`, JNA paths) are extracted from the SDK's `product-info.json` by the launcher script, matching the correct OS and architecture.

## Launcher Script

`scalij` handles:
- IntelliJ installation detection (installed app or sbt-idea-plugin SDK, both IC and IU)
- Scala plugin version matching (extracts IDE build number, picks compatible plugin)
- JVM args from `product-info.json` (boot classpath, `--add-opens`, PathClassLoader, JNA, etc.) with OS+arch matching
- Classpath assembly: IntelliJ SDK JARs + LSP server JARs (with plugin.xml for extension point registration) + Scala plugin JARs + Java plugin JARs
- JDK table copy from user's IntelliJ config
- CDS warning suppression (`-Xlog:cds=off`)
- Daemon management (`--daemon`, `--stop`, auto-start connect mode)
- Entry point: `com.intellij.idea.Main scala-lsp [--daemon]` for LSP modes, `com.intellij.idea.Main scala-lsp-import` for project import
- `socat` stdio↔TCP proxy
- Editor setup (`--setup-claude-code-mcp`, `--setup-claude-code-lsp`, `--setup-vscode`, `--setup-zed`)

## State and Caching

```
~/.cache/intellij-scala-lsp/
  daemon.pid                  # daemon process ID (written after port bind)
  daemon.port                 # TCP port (written after port bind)
  sources/                    # cached JAR source files (file:// URIs)
  system/                     # IntelliJ indexes and caches (persistent across restarts)
    caches/                   # VFS, stub indexes, file-based indexes
    log/idea.log              # IntelliJ's internal log
  config/                     # IntelliJ config (isolated from user's IDE)
    options/jdk.table.xml     # copied from user's IntelliJ config on first start
```

The `system/` directory persists indexes across daemon restarts. On warm restart, IntelliJ scans 92k+ files but finds 0 needing re-indexing — startup is fast.
