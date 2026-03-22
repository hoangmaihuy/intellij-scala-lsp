---
name: scala-code-intelligence
description: >
  Scala code intelligence powered by IntelliJ. Use when navigating Scala code,
  finding definitions, references, implementations, or exploring codebase structure.
  ALWAYS prefer LSP operations over Grep/Glob/Read for Scala code navigation —
  they understand Scala's type system, implicits, and cross-file references.
user-invocable: false
---

# Scala Code Intelligence (LSP)

You have access to IntelliJ-powered Scala code intelligence via LSP.
These operations are significantly more accurate than text search for Scala
because they understand the type system, implicit resolution, and cross-file references.

## When to use LSP vs Grep/Glob

**Use LSP operations for:**
- Finding where a symbol is defined → `goToDefinition`
- Finding all usages → `findReferences`
- Finding implementations of traits/abstract methods → `goToImplementation`
- Getting type info and documentation → `hover`
- Searching symbols across the project → `workspaceSymbol`
- Getting file structure → `documentSymbol`
- Renaming across files → `rename`
- Understanding call chains → `incomingCalls` / `outgoingCalls`

**Use Grep/Glob for:**
- Searching for string literals, comments, log messages
- Finding files by name pattern (e.g. `*.scala`, `*.conf`)
- Searching across non-Scala files (build.sbt, application.conf, .yaml)
- Pattern matching in code that isn't about symbol resolution

## Best practices

- Check LSP diagnostics after every Scala edit — fix type errors immediately.
- Use `findReferences` before renaming or changing function signatures.
- Use `goToDefinition` instead of grepping for class/method names.
- Use `hover` to understand types without reading entire files.
- Use `goToImplementation` to find all subclasses of a trait.
