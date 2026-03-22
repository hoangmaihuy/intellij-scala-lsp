---
name: scala-code-intelligence
description: >
  Scala code intelligence powered by IntelliJ. Use when working with Scala code —
  reading, navigating, editing, refactoring, finding definitions, references,
  implementations, or exploring codebase structure. ALWAYS prefer LSP operations
  over Grep/Glob/Read for Scala code navigation — they understand Scala's type system,
  implicits, and cross-file references. Use this skill whenever you encounter .scala
  files, even if the user doesn't explicitly mention code intelligence.
user-invocable: true
---

# Scala Code Intelligence (LSP)

You have IntelliJ-powered LSP operations for Scala. These resolve symbols through
the type system — they follow implicits, type aliases, and inheritance chains
that text search cannot see. A grep for `process` might find 200 string matches;
`findReferences` on `MyService.process` finds only the actual call sites.

## Choosing between LSP and Grep/Glob

**Use LSP operations** whenever you need to understand Scala code structure:
- "Where is X defined?" → `goToDefinition`
- "Who calls X?" / "Where is X used?" → `findReferences`
- "What classes implement trait X?" → `goToImplementation`
- "What type is X?" / "What does X extend?" → `hover`
- "What symbols exist matching X?" → `workspaceSymbol`
- "What's in this file?" → `documentSymbol`
- "Rename X everywhere" → `rename`

**Use Grep/Glob** only for text that isn't a Scala symbol:
- String literals, comments, log messages
- File name patterns (`*.scala`, `*.conf`)
- Non-Scala files (build.sbt, application.conf, YAML)
- Regex patterns in code content

## LSP operations

### Navigation

| Operation | What it does |
|-----------|-------------|
| `goToDefinition` | Jump to where a symbol is defined — read the source |
| `findReferences` | Find all usages of a symbol across the codebase |
| `goToImplementation` | Find all implementations of a trait or abstract method |
| `hover` | Type signature, documentation, supertypes, subtypes |
| `workspaceSymbol` | Search for symbols by name across the project |
| `documentSymbol` | List all symbols in a file (classes, methods, vals) |

### Editing

| Operation | What it does |
|-----------|-------------|
| `rename` | Rename a symbol and update all references across files |

### Maintenance

Check LSP diagnostics for errors and warnings after editing Scala files.

## Workflow guidelines

**After every Scala edit**, check diagnostics on the changed file. Scala's type
system catches errors that look fine textually — a missing implicit, a wrong type
parameter, an ambiguous overload. Catching these immediately avoids cascading failures.

**Before renaming or changing a signature**, call `findReferences` first. This shows
every call site so you know the blast radius before making the change.

**When exploring unfamiliar code**, start with `workspaceSymbol` to find the
entry point, then `goToDefinition` to read it, then `findReferences` or
`goToImplementation` to trace the flow. This is faster and more reliable than
grepping through files.
