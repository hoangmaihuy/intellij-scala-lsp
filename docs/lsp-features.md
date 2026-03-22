# LSP Features

intellij-scala-lsp implements 30+ LSP methods, all backed by IntelliJ IDEA's Scala analysis engine.

## Navigation

| LSP Method | Description |
|---|---|
| `textDocument/definition` | Go to definition via PSI reference resolution |
| `textDocument/typeDefinition` | Go to the type's definition (e.g. from a variable to its type's class) |
| `textDocument/implementation` | Find all implementations of a trait, class, or abstract method |
| `textDocument/references` | Find all references across the project |
| `textDocument/documentSymbol` | Outline of classes, traits, objects, methods, vals in a file |
| `workspace/symbol` | Search symbols across the entire project |
| `textDocument/documentLink` | Clickable links to imported types |
| `textDocument/documentHighlight` | Highlight all occurrences of a symbol in a file |

## Hierarchy

| LSP Method | Description |
|---|---|
| `callHierarchy/prepare,incomingCalls,outgoingCalls` | Call hierarchy — who calls this, what does this call |
| `typeHierarchy/prepare,supertypes,subtypes` | Type hierarchy — supertypes and subtypes of a class/trait |

## Editing

| LSP Method | Description |
|---|---|
| `textDocument/completion` | Code completion with lazy resolve, snippets, and auto-import |
| `completionItem/resolve` | Resolve detail, documentation, and additional edits on demand |
| `textDocument/signatureHelp` | Parameter info for method calls (triggers on `(` and `,`) |
| `textDocument/codeAction` | Quick fixes, intention actions, and refactorings |
| `codeAction/resolve` | Compute workspace edits for a code action |
| `textDocument/rename` | Rename symbol across all usages (with `prepareRename` support) |
| `textDocument/formatting` | Format entire document via IntelliJ's code style |
| `textDocument/rangeFormatting` | Format selected range |
| `textDocument/onTypeFormatting` | Auto-indent, triple-quote close, brace block reformat (triggers on `\n`, `"`, `}`) |
| `workspace/executeCommand` | `scala.organizeImports`, `scala.reformat`, `scala.gotoLocation` |
| `workspace/willRenameFiles` | Auto-rename types when renaming `.scala` files |

## Display

| LSP Method | Description |
|---|---|
| `textDocument/hover` | Type info + ScalaDoc as markdown |
| `textDocument/publishDiagnostics` | Errors and warnings pushed from IntelliJ's analysis engine |
| `textDocument/inlayHint` | Type annotations for vals, parameters, return types (with resolve) |
| `textDocument/semanticTokens/full` | Full semantic token classification (16 token types, 8 modifiers) |
| `textDocument/semanticTokens/range` | Semantic tokens for a range |
| `textDocument/codeLens` | "Overrides" annotations on trait implementations |
| `textDocument/foldingRange` | Code folding regions |
| `textDocument/selectionRange` | Structural selection ranges by walking PSI tree |

## Document Sync

| LSP Method | Description |
|---|---|
| `textDocument/didOpen,didChange,didClose,didSave` | Full text document synchronization |
| `workspace/didChangeWorkspaceFolders` | Multi-root workspace support |
| `workspace/didChangeWatchedFiles` | External file change notification |
