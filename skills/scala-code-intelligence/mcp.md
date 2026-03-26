---
name: scala-code-intelligence
description: >
  Scala code intelligence powered by IntelliJ. Use when working with Scala code —
  reading, navigating, editing, refactoring, finding definitions, references, usages,
  implementations, or exploring codebase structure. ALWAYS prefer these MCP tools
  over Grep/Glob/Read for Scala code navigation — they understand Scala's type system,
  implicits, and cross-file references. Use this skill whenever you encounter .scala
  files, even if the user doesn't explicitly mention code intelligence. This includes:
  reading Scala source files (use `definition` instead of `Read`), finding where something
  is defined or used, understanding type hierarchies, renaming symbols, checking for
  compile errors, formatting code, exploring what's in a file, or tracing how code flows
  through a Scala codebase. If the task involves .scala files in ANY way — even just
  "look at this file" or "what does this class do" — use these tools.
user-invocable: true
---

# Scala Code Intelligence (MCP)

You have IntelliJ-powered MCP tools for Scala. These resolve symbols through the
type system — they follow implicits, type aliases, and inheritance chains that text
search cannot see. A grep for `process` might find 200 string matches; `references`
with `symbolName: "MyService.process"` finds only the actual call sites.

## When to use MCP tools vs Grep/Glob/Read

**Default to MCP tools** for anything involving Scala code:
- Reading a .scala file → `definition` (returns source with line numbers, understands symbol boundaries)
- "Where is X defined?" → `definition`
- "Who calls X?" / "Find usages" → `references`
- "What implements trait X?" → `implementations`
- "What type is this?" / "What does X extend?" → `hover`
- "Find classes/methods matching X" → `workspace_symbols`
- "What's in this file?" → `document_symbols`
- "Rename X everywhere" → `rename_symbol`
- "Are there compile errors?" → `diagnostics`
- "Format this file" → `format`
- "Clean up imports" → `organize_imports`

**Fall back to Grep/Glob only** for non-symbol text:
- String literals, comments, log messages, config values
- File name patterns (`*.scala`, `*.conf`)
- Non-Scala files (build.sbt, application.conf, YAML, XML)
- Regex patterns within code content

## symbolName resolution

The `symbolName` parameter accepts several formats and uses smart matching:

| Format | Example | When to use |
|--------|---------|-------------|
| Simple name | `"UserService"` | When the name is unique enough |
| Qualified name | `"UserService.findById"` | To target a specific member |
| Package-qualified | `"io.circe.Json"` | To disambiguate common names |

**Matching priority:** exact match > companion object > qualified match > suffix match.
If an exact match exists, suffix matches are dropped automatically. If only suffix
matches exist, the tool includes a hint suggesting `filePath+line+column` for precision.

**Use `filePath` + `line` + `column` instead of `symbolName` when:**
- You have a cursor position (e.g., from a diagnostics error location)
- The symbol name is ambiguous (overloaded methods, common names)
- Targeting an external/library symbol
- `symbolName` returned too many or wrong matches

All line/column parameters are **1-indexed**.

## Tool reference

### Navigation tools

**`definition`** — Read the full source code where a symbol is defined.
- Params: `symbolName` OR `filePath` + `line` + `column`
- Returns: full source with line numbers (up to 3 results shown in full; extras listed as summaries)
- Use this instead of `Read` for Scala files — it understands symbol boundaries and returns the enclosing class/method body

**`references`** — Find all usages of a symbol across the codebase.
- Params: `symbolName` OR `filePath` + `line` + `column`
- Returns: all usage locations grouped by file, with 2 lines of context around each reference
- Does NOT include the declaration itself — only call sites and usages

**`implementations`** — Find all implementations of a trait, abstract class, or abstract method.
- Params: `symbolName` OR `filePath` + `line` + `column`
- Returns: full source of each implementation (up to 10 in full; beyond 10 shows location + first line only)

### Info tools

**`hover`** — Get type signature, documentation, supertypes, and subtypes for a symbol.
- Params: `symbolName` OR `filePath` + `line` + `column`
- Returns: type info, docs, type definition location, supertype list, subtype list
- All type hierarchy info is best-effort — may be partial if the LSP can't resolve everything

**`workspace_symbols`** — Search for symbols by name across the entire project.
- Params: `query` (required)
- Returns: list of matching symbols with kind, container, file path, and line number
- Good for discovery — use when you don't know the exact name or location

**`document_symbols`** — List all symbols in a single file as a hierarchical outline.
- Params: `filePath` (required)
- Returns: nested tree of classes, methods, fields, etc. with line numbers

**`diagnostics`** — Get compiler errors and warnings for a file.
- Params: `filePath` (required)
- Returns: each diagnostic with severity (ERROR/WARNING/INFO/HINT), location, message, and the actual source line

### Editing tools

**`rename_symbol`** — Rename a symbol and update all references across the codebase.
- Params: `filePath` + `line` + `column` + `newName` (all required)
- Applies changes to disk immediately
- Returns: count of occurrences changed and list of modified files

**`code_actions`** — Get available quick fixes and refactorings for a code range.
- Params: `filePath` + `startLine` + `startColumn` + `endLine` + `endColumn` (all required)
- Returns: numbered list of available actions (e.g., "Add explicit type", "Extract to method")
- Results are cached for `apply_code_action` — each new call replaces the cache

**`apply_code_action`** — Apply one of the actions from the most recent `code_actions` call.
- Params: `filePath` + `actionIndex` (1-indexed, from code_actions output)
- The `filePath` must match the file used in the `code_actions` call
- Applies changes to disk immediately

### Formatting tools

**`format`** — Format Scala code using IntelliJ code style.
- Params: `filePath` (required), `startLine` + `endLine` (optional, for range formatting)
- Applies formatting to disk immediately

**`organize_imports`** — Remove unused imports and sort remaining imports.
- Params: `filePath` (required)

## Workflow patterns

### After editing Scala code
Always run `diagnostics` on changed files. Scala's type system catches errors that
look correct textually — missing implicits, wrong type parameters, ambiguous overloads.
Catching these immediately prevents cascading failures.

### Before renaming or changing a signature
Call `references` first to see every call site. This shows the blast radius before
you make the change. Then use `rename_symbol` for the actual rename — it updates all
references atomically.

### After adding or removing code
Run `organize_imports` to clean up. Then `format` if the indentation is off.

### Exploring unfamiliar code
1. `workspace_symbols` with a query to find entry points
2. `definition` to read the source
3. `hover` to understand types and inheritance
4. `references` or `implementations` to trace the flow

This is faster and more reliable than grepping through files because it follows the
type system rather than matching text.

### Fixing compile errors
1. `diagnostics` to get the error with its exact location
2. Use the `line` and `column` from the diagnostic with `hover` or `definition` to understand the context
3. Fix the code, then `diagnostics` again to verify

### Using code actions for quick fixes
1. `diagnostics` to find the error location
2. `code_actions` with a range covering the error
3. `apply_code_action` with the index of the desired fix
