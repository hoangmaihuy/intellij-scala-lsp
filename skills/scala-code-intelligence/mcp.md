---
name: scala-code-intelligence
description: >
  Scala code intelligence powered by IntelliJ. Use when working with Scala code —
  reading, navigating, editing, refactoring, finding definitions, references, usages,
  implementations, or exploring codebase structure. ALWAYS prefer these MCP tools
  over Grep/Glob/Read for Scala code navigation — they understand Scala's type system,
  implicits, and cross-file references. Use this skill whenever you encounter .scala
  files, even if the user doesn't explicitly mention code intelligence.
user-invocable: true
---

# Scala Code Intelligence (MCP)

You have IntelliJ-powered MCP tools for Scala. These resolve symbols through
the type system — they follow implicits, type aliases, and inheritance chains
that text search cannot see. A grep for `process` might find 200 string matches;
`references` with symbolName `MyService.process` finds only the actual call sites.

## Choosing between MCP tools and Grep/Glob

**Use MCP tools** whenever you need to understand Scala code structure:
- "Where is X defined?" → `definition`
- "Who calls X?" / "Where is X used?" / "Find X usages" → `references`
- "What classes implement trait X?" → `implementations`
- "What type is X?" / "What does X extend?" → `hover`
- "What symbols exist matching X?" → `workspace_symbols`
- "What's in this file?" → `document_symbols`
- "Rename X everywhere" → `rename_symbol`
- "Are there compile errors?" → `diagnostics`

**Use Grep/Glob** only for text that isn't a Scala symbol:
- String literals, comments, log messages
- File name patterns (`*.scala`, `*.conf`)
- Non-Scala files (build.sbt, application.conf, YAML)
- Regex patterns in code content

## Tool quick reference

### Navigation (prefer `symbolName` — no need to know file paths)

| Tool | What it does | Example input |
|------|-------------|---------------|
| `definition` | Read source where a symbol is defined | `symbolName: "UserService.findById"` |
| `references` | Find all usages across the codebase | `symbolName: "UserService"` |
| `implementations` | Find trait/class implementations | `symbolName: "Repository"` |
| `hover` | Type signature, docs, supertypes, subtypes | `symbolName: "Config.dbUrl"` |
| `workspace_symbols` | Search symbols by name pattern | `query: "Service"` |
| `document_symbols` | List all symbols in a file | `filePath: "/path/to/File.scala"` |

Use `filePath` + `line` + `column` instead of `symbolName` when:
- You already have a cursor position (e.g., from a diagnostics error)
- The symbol name is ambiguous (overloaded methods)
- Targeting an external/library symbol

### Editing

| Tool | What it does | Required params |
|------|-------------|-----------------|
| `rename_symbol` | Rename across all files | `filePath`, `line`, `column`, `newName` |
| `code_actions` | Get quick fixes / refactorings for a range | `filePath`, `startLine`, `startColumn`, `endLine`, `endColumn` |
| `apply_code_action` | Apply a fix from `code_actions` result | `filePath`, `actionIndex` |

### Maintenance

| Tool | What it does | Required params |
|------|-------------|-----------------|
| `diagnostics` | Errors and warnings from the Scala compiler | `filePath` |
| `format` | Format code (whole file or line range) | `filePath` (optional `startLine`, `endLine`) |
| `organize_imports` | Remove unused imports, sort remaining | `filePath` |

## Workflow guidelines

**After every Scala edit**, check `diagnostics` on the changed file. Scala's type
system catches errors that look fine textually — a missing implicit, a wrong type
parameter, an ambiguous overload. Catching these immediately avoids cascading failures.

**Before renaming or changing a signature**, call `references` first. This shows
every call site so you know the blast radius before making the change.

**After adding or removing code**, run `organize_imports` to clean up. Stale imports
cause compiler warnings and clutter.

**When exploring unfamiliar code**, start with `workspace_symbols` to find the
entry point, then `definition` to read it, then `references` or `implementations`
to trace the flow. This is faster and more reliable than grepping through files.
