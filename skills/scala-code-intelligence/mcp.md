---
name: scala-code-intelligence
description: >
  Scala code intelligence powered by IntelliJ. Use when navigating Scala code,
  finding definitions, references, implementations, or exploring codebase structure.
  ALWAYS prefer these MCP tools over Grep/Glob/Read for Scala code navigation —
  they understand Scala's type system, implicits, and cross-file references.
user-invocable: false
---

# Scala Code Intelligence (MCP)

You have access to IntelliJ-powered Scala code intelligence via MCP tools.
These are significantly more accurate than text search for Scala because they
understand the type system, implicit resolution, and cross-file references.

## When to use MCP tools vs Grep/Glob

**Use MCP tools for:**
- Finding where a class, trait, method, or val is defined → `definition`
- Finding all usages of a symbol → `references`
- Finding all implementations of a trait or abstract method → `implementations`
- Getting type info, docs, supertypes, subtypes → `hover`
- Searching for symbols across the project → `workspace_symbols`
- Getting a file's structure (classes, methods, vals) → `document_symbols`
- Renaming a symbol across all files → `rename_symbol`
- Getting errors and warnings → `diagnostics`

**Use Grep/Glob for:**
- Searching for string literals, comments, log messages
- Finding files by name pattern (e.g. `*.scala`, `*.conf`)
- Searching across non-Scala files (build.sbt, application.conf, .yaml)
- Pattern matching in code that isn't about symbol resolution

## Tool reference

| Task | MCP Tool | Input |
|------|----------|-------|
| Go to definition | `definition` | `symbolName` or `filePath+line+column` |
| Find all usages | `references` | `symbolName` or `filePath+line+column` |
| Find implementations | `implementations` | `symbolName` or `filePath+line+column` |
| Type info + docs | `hover` | `symbolName` or `filePath+line+column` |
| Search project symbols | `workspace_symbols` | `query` |
| File outline | `document_symbols` | `filePath` |
| Rename symbol | `rename_symbol` | `filePath+line+column+newName` |
| Check for errors | `diagnostics` | `filePath` |
| Quick fixes | `code_actions` | `filePath+range` |
| Apply a fix | `apply_code_action` | `filePath+actionIndex` |
| Format code | `format` | `filePath` (optional `startLine+endLine`) |
| Clean up imports | `organize_imports` | `filePath` |

## Best practices

- **Prefer `symbolName`** for discovery tools (definition, references, implementations, hover).
  Just pass the name like `"MyClass"` or `"MyClass.myMethod"` — no need to know file paths.
- **Use `filePath+line+column`** when you have a specific cursor position, or for external/library symbols.
- **Check `diagnostics` after editing** Scala code. Fix type errors before moving on.
- **Call `references` before renaming** to understand the impact.
- **Use `organize_imports`** after adding new imports or removing code.
- **Use `format`** after significant structural changes.
