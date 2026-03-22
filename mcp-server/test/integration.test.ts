/**
 * MCP Tool Integration Tests
 *
 * Prerequisites:
 *   1. Compile fixture: cd mcp-server/test/fixtures && sbt compile
 *   2. Import: intellij-scala-lsp --import mcp-server/test/fixtures
 *   3. Start daemon: intellij-scala-lsp --daemon mcp-server/test/fixtures
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

describe('definition', () => {
  it('should return full source by symbol name', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toContain('case class Circle');
    expect(result).toContain('radius: Double');
    expect(result).toContain('Circle.scala');
  });

  it('should return source for a trait', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Shape' });
    expect(result).toContain('trait Shape');
    expect(result).toContain('Shape.scala');
  });

  it('should include line numbers', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toMatch(/\d+\|/);
  });

  it('should work with filePath+line+column', async () => {
    // Circle.scala line 3 col 43: "Shape" in "extends Shape:"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.circle,
      line: 3,
      column: 43,
    });
    expect(result).toContain('Shape');
    expect(result).toContain('Shape.scala');
  });

  it('should return error for non-existent symbol', async () => {
    const result = await tools.callTool('definition', { symbolName: 'NonExistentClass' });
    expect(result).toContain('No symbol found');
  });
});

// ── References ───────────────────────────────────────────────────────

describe('references', () => {
  it('should find usages by symbol name', async () => {
    const result = await tools.callTool('references', { symbolName: 'Circle' });
    expect(result).toMatch(/\d+\|/);
    expect(result).toContain('.scala');
  });

  it('should find references by position', async () => {
    // Shape.scala line 5 col 7: "area" method
    const result = await tools.callTool('references', {
      filePath: FIXTURES.shape,
      line: 5,
      column: 7,
    });
    expect(result).toContain('.scala');
  });
});

// ── Implementations ──────────────────────────────────────────────────

describe('implementations', () => {
  it('should find implementations by symbol name', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'Shape' });
    expect(result).toContain('Circle');
    expect(result).toContain('Rectangle');
  });

  it('should return full source for implementations', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'Shape' });
    expect(result).toContain('override def area');
  });

  it('should work by position', async () => {
    // Shape.scala line 4 col 7: "Shape" trait name
    const result = await tools.callTool('implementations', {
      filePath: FIXTURES.shape,
      line: 4,
      column: 7,
    });
    expect(result).toMatch(/Circle|Rectangle/);
  });
});

// ── Hover ────────────────────────────────────────────────────────────

describe('hover', () => {
  it('should return type info by symbol name', async () => {
    const result = await tools.callTool('hover', { symbolName: 'Circle' });
    expect(result).toContain('Circle');
  });

  it('should return type info by position', async () => {
    // Circle.scala line 4 col 17: "area" method
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.circle,
      line: 4,
      column: 17,
    });
    expect(result).toContain('Double');
  });

  it('should include supertypes via position', async () => {
    // Position on "Circle" class name — type hierarchy works better at class definition site
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.circle,
      line: 3,
      column: 14, // on "Circle"
    });
    expect(result).toMatch(/Supertypes.*Shape|extends.*Shape/);
  });

  it('should include subtypes via position', async () => {
    // Position on "Shape" trait name
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.shape,
      line: 4,
      column: 7, // on "Shape"
    });
    expect(result).toMatch(/Subtypes.*Circle|Subtypes.*Rectangle/);
  });

  it('should return error when no args', async () => {
    const result = await tools.callTool('hover', {});
    expect(result).toContain('Provide either');
  });
});

// ── Diagnostics ──────────────────────────────────────────────────────

describe('diagnostics', () => {
  it('should report errors for file with type mismatches', async () => {
    const result = await tools.callTool('diagnostics', { filePath: FIXTURES.errors });
    expect(result).toMatch(/ERROR/i);
    expect(result).toContain('diagnostic');
  });

  it('should report no diagnostics for clean file', async () => {
    const result = await tools.callTool('diagnostics', { filePath: FIXTURES.circle });
    expect(result).toMatch(/[Nn]o diagnostics|0 diagnostic/);
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
  it('should find symbols across the project', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Shape' });
    expect(result).toContain('Shape');
    expect(result).toMatch(/trait|class|object/);
  });

  it('should find methods', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'totalArea' });
    expect(result).toContain('totalArea');
  });

  it('should return message for no matches', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'zzzzNonExistent' });
    expect(result).toContain('No symbols found');
  });
});

// ── External Library Symbols ─────────────────────────────────────

describe('external library: workspace_symbols', () => {
  it('simple query "Monad" should find cats.Monad', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Monad' });
    expect(result).toContain('Found');
    expect(result).not.toContain('No symbols found');
    // Should contain Monad with cats container
    expect(result).toMatch(/Monad.*cats|cats.*Monad/);
  });

  it('simple query "ZIO" should find zio.ZIO', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'ZIO' });
    expect(result).toContain('Found');
    expect(result).not.toContain('No symbols found');
    expect(result).toMatch(/ZIO.*zio|zio.*ZIO/);
  });
});

describe('external library: definition via symbolName', () => {
  it('"Monad" should return definition with source code', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Monad' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/\d+\|/); // has line-numbered source
    expect(result).toMatch(/Monad/);
  });

  it('"ZIO" should return definition with source code', async () => {
    const result = await tools.callTool('definition', { symbolName: 'ZIO' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/\d+\|/);
    expect(result).toMatch(/ZIO/);
  });

  it('"cats.Monad" FQN should find Monad from cats', async () => {
    const result = await tools.callTool('definition', { symbolName: 'cats.Monad' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/Monad/);
    expect(result).toMatch(/\d+\|/);
  });

  it('"zio.ZIO" FQN should find ZIO from zio', async () => {
    const result = await tools.callTool('definition', { symbolName: 'zio.ZIO' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/ZIO/);
    expect(result).toMatch(/\d+\|/);
  });
});

describe('external library: definition via position', () => {
  it('Monad import should go to cats.Monad definition', async () => {
    // ExternalDeps.scala line 3: "import cats.Monad"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13,
    });
    expect(result).not.toContain('No definition found');
    expect(result).toMatch(/Monad/);
    expect(result).toMatch(/\d+\|/);
  });

  it('ZIO import should go to zio.ZIO definition', async () => {
    // ExternalDeps.scala line 5: "import zio.{ZIO, Task}"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 5,
      column: 13,
    });
    expect(result).toMatch(/ZIO/);
    expect(result).toMatch(/\d+\|/);
  });
});

describe('external library: references', () => {
  it('"cats.Monad" FQN should find references in project', async () => {
    const result = await tools.callTool('references', { symbolName: 'cats.Monad' });
    expect(result).not.toContain('No symbol found');
    expect(result).toContain('ExternalDeps.scala');
  });

  it('"zio.ZIO" FQN should find references in project', async () => {
    const result = await tools.callTool('references', { symbolName: 'zio.ZIO' });
    expect(result).not.toContain('No symbol found');
    expect(result).toContain('ExternalDeps.scala');
  });

  it('Monad reference by position should find project usages', async () => {
    const result = await tools.callTool('references', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13,
    });
    expect(result).not.toContain('No references found');
    expect(result).toContain('ExternalDeps.scala');
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
