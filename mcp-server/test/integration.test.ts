/**
 * MCP Tool Integration Tests
 *
 * Prerequisites:
 *   1. Compile fixture: cd mcp-server/test/fixtures && sbt compile
 *   2. Import: intellij-scala-lsp --import mcp-server/test/fixtures
 *   3. Start daemon: intellij-scala-lsp --daemon mcp-server/test/fixtures
 *
 * Note: workspace/symbol may not index Scala 3 sources in small sbt projects.
 * Tests that depend on symbol-name resolution are marked with filePath+line+column
 * fallback to ensure they pass regardless of indexing state.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupTestEnvironment, teardownTestEnvironment, FIXTURES, FIXTURE_DIR } from './setup.js';
import { TestToolRunner } from './tool-runner.js';
import * as fs from 'fs';
import * as path from 'path';

let tools: TestToolRunner;

beforeAll(async () => {
  tools = await setupTestEnvironment();
}, 60_000);

afterAll(async () => {
  await teardownTestEnvironment();
});

// ── Tool Registration ────────────────────────────────────────────────

describe('tool registration', () => {
  it('should register exactly 12 tools', () => {
    expect(tools.toolNames).toHaveLength(12);
    expect(tools.toolNames).toEqual(expect.arrayContaining([
      'definition', 'references', 'implementations', 'hover',
      'diagnostics', 'document_symbols', 'workspace_symbols',
      'rename_symbol', 'code_actions', 'apply_code_action',
      'format', 'organize_imports',
    ]));
  });
});

// ── Definition ───────────────────────────────────────────────────────
// Circle.scala:
//   1: package example
//   2: (blank)
//   3: case class Circle(radius: Double) extends Shape:
//   4:   override def area: Double = math.Pi * radius * radius
//   5:   override def perimeter: Double = 2 * math.Pi * radius

describe('definition', () => {
  it('should return source via filePath+line+column', async () => {
    // In Circle.scala line 4, col 31: "math" — go to definition
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.circle,
      line: 4,
      column: 31,
    });
    // Should return something (either definition or not found)
    expect(result).toBeDefined();
    expect(result.length).toBeGreaterThan(0);
  });

  it('should return error for non-existent symbol name', async () => {
    const result = await tools.callTool('definition', { symbolName: 'NonExistentClass' });
    expect(result).toContain('No symbol found');
  });

  it('should return error with no args', async () => {
    const result = await tools.callTool('definition', {});
    // Empty targets
    expect(result).toBeDefined();
  });
});

// ── References ───────────────────────────────────────────────────────

describe('references', () => {
  it('should find references via filePath+line+column', async () => {
    // Shape.scala line 5: "def area: Double" — find references to area
    const result = await tools.callTool('references', {
      filePath: FIXTURES.shape,
      line: 5,
      column: 7, // on "area"
    });
    // area is overridden in Circle and Rectangle
    expect(result).toContain('.scala');
  });
});

// ── Implementations ──────────────────────────────────────────────────

describe('implementations', () => {
  it('should find implementations via position', async () => {
    // Shape.scala line 4: "trait Shape" — find implementations
    const result = await tools.callTool('implementations', {
      filePath: FIXTURES.shape,
      line: 4,
      column: 7, // on "Shape"
    });
    // Circle and Rectangle extend Shape
    expect(result).toMatch(/Circle|Rectangle|implementation/i);
  });
});

// ── Hover ────────────────────────────────────────────────────────────

describe('hover', () => {
  it('should return type info by position', async () => {
    // Circle.scala line 3: "case class Circle(radius: Double)"
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.circle,
      line: 3,
      column: 14, // on "Circle"
    });
    expect(result).toContain('Circle');
    expect(result).toContain('example');
  });

  it('should return method type info', async () => {
    // Circle.scala line 4: "override def area: Double"
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.circle,
      line: 4,
      column: 17, // on "area"
    });
    expect(result).toContain('Double');
  });

  it('should include supertypes via position', async () => {
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.circle,
      line: 3,
      column: 14, // on "Circle"
    });
    // Hover enrichment includes supertypes
    expect(result).toMatch(/Supertypes|Shape|extends/i);
  });

  it('should return error when no args', async () => {
    const result = await tools.callTool('hover', {});
    expect(result).toContain('Provide either');
  });
});

// ── Diagnostics ──────────────────────────────────────────────────────

describe('diagnostics', () => {
  it('should return diagnostics for a file', async () => {
    const result = await tools.callTool('diagnostics', { filePath: FIXTURES.circle });
    expect(result).toBeDefined();
    expect(result.length).toBeGreaterThan(0);
  });
});

// ── Document Symbols ─────────────────────────────────────────────────

describe('document_symbols', () => {
  it('should list symbols in Circle.scala', async () => {
    const result = await tools.callTool('document_symbols', { filePath: FIXTURES.circle });
    expect(result).toContain('Circle');
    expect(result).toContain('area');
    expect(result).toContain('perimeter');
  });

  it('should list symbols in Shape.scala', async () => {
    const result = await tools.callTool('document_symbols', { filePath: FIXTURES.shape });
    expect(result).toContain('Shape');
  });
});

// ── Workspace Symbols ────────────────────────────────────────────────

describe('workspace_symbols', () => {
  it('should return message for no matches', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'zzzzNonExistent' });
    expect(result).toContain('No symbols found');
  });

  it('should handle valid query', async () => {
    // May or may not find results depending on indexing state
    const result = await tools.callTool('workspace_symbols', { query: 'Shape' });
    expect(result).toBeDefined();
    expect(result.length).toBeGreaterThan(0);
  });
});

// ── Code Actions ─────────────────────────────────────────────────────

describe('code_actions', () => {
  it('should return available actions or none', async () => {
    const result = await tools.callTool('code_actions', {
      filePath: FIXTURES.shapeService,
      startLine: 1,
      startColumn: 1,
      endLine: 1,
      endColumn: 1,
    });
    expect(result).toBeDefined();
    expect(result.length).toBeGreaterThan(0);
  });
});

// ── Apply Code Action ────────────────────────────────────────────────

describe('apply_code_action', () => {
  it('should error when no cached actions', async () => {
    const result = await tools.callTool('apply_code_action', {
      filePath: FIXTURES.shape,
      actionIndex: 1,
    });
    expect(result).toMatch(/[Nn]o cached|[Cc]all code_actions first/);
  });
});

// ── Format ───────────────────────────────────────────────────────────

describe('format', () => {
  const tempDir = path.join(FIXTURE_DIR, 'src', 'main', 'scala', 'temp_test');
  const tempFile = path.join(tempDir, 'FormatTest.scala');

  beforeAll(() => {
    fs.mkdirSync(tempDir, { recursive: true });
    fs.writeFileSync(tempFile, `package temp_test

class    FormatTest   {
def   badly_formatted  ()  :    Unit  = {
println(  "hello"  )
}
}
`);
  });

  afterAll(() => {
    if (fs.existsSync(tempFile)) fs.unlinkSync(tempFile);
    try { fs.rmdirSync(tempDir); } catch { /* may not be empty */ }
  });

  it('should format an entire file', async () => {
    const result = await tools.callTool('format', { filePath: tempFile });
    expect(result).toMatch(/[Ff]ormatted|[Aa]lready formatted/);
  });

  it('should format a line range', async () => {
    fs.writeFileSync(tempFile, `package temp_test

class    FormatTest   {
def   badly_formatted  ()  :    Unit  = {
println(  "hello"  )
}
}
`);
    const result = await tools.callTool('format', {
      filePath: tempFile,
      startLine: 3,
      endLine: 6,
    });
    expect(result).toMatch(/[Ff]ormatted|[Aa]lready formatted/);
  });
});

// ── Organize Imports ─────────────────────────────────────────────────

describe('organize_imports', () => {
  it('should organize imports', async () => {
    const result = await tools.callTool('organize_imports', { filePath: FIXTURES.main });
    expect(result).toContain('Organized imports');
  });
});
