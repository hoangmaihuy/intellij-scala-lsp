/**
 * Integration tests — verify MCP tools work end-to-end against the LSP daemon.
 *
 * These tests catch regressions in:
 *   - workspace_symbols: both simple ("Json") and FQN ("io.circe.Json") queries
 *   - definition: external library symbols via symbolName and filePath+line+column
 *   - references: finding project usages of external library symbols
 *   - implementations: finding trait implementations
 *   - match quality: exact vs suffix sorting
 *
 * Prerequisites:
 *   1. cd mcp-server/test/fixtures && sbt compile
 *   2. intellij-scala-lsp --daemon mcp-server/test/fixtures
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupTestEnvironment, teardownTestEnvironment, FIXTURES, FIXTURE_DIR } from './setup.js';
import { TestToolRunner } from './tool-runner.js';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';

let tools: TestToolRunner;

beforeAll(async () => {
  tools = await setupTestEnvironment();
}, 60_000);

afterAll(async () => {
  await teardownTestEnvironment();
});

// ── workspace_symbols ───────────────────────────────────────────────

describe('workspace_symbols', () => {
  it('simple query "Shape" should find project symbol', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Shape' });
    expect(result).toContain('Found');
    expect(result).toContain('Shape');
    expect(result).toContain('Shape.scala');
  });

  it('simple query "Circle" should find project symbol', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Circle' });
    expect(result).toContain('Found');
    expect(result).toContain('Circle');
  });

  it('simple query "Monad" should find library symbol', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Monad' });
    // Must find results (not "No symbols found")
    expect(result).toContain('Found');
    expect(result).toMatch(/Monad/);
    // Must NOT be an error
    expect(result).not.toContain('No symbols found');
  });

  it('simple query "ZIO" should find library symbol', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'ZIO' });
    expect(result).toContain('Found');
    expect(result).toMatch(/ZIO/);
    expect(result).not.toContain('No symbols found');
  });

  it('FQN query "example.Shape" should find project symbol and include container', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'example.Shape' });
    expect(result).toContain('Found');
    expect(result).toContain('Shape');
    // Verify containerName is present
    expect(result).toContain('in example');
  });

  it('empty query should return no results', async () => {
    const result = await tools.callTool('workspace_symbols', { query: '' });
    expect(result).toContain('No symbols found');
  });

  it('nonexistent symbol should return no results', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'ZzNonExistent99' });
    expect(result).toContain('No symbols found');
  });
});

// ── definition via symbolName ───────────────────────────────────────

describe('definition via symbolName', () => {
  it('"Shape" should return trait definition with source code', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Shape' });
    // Must contain actual source code, not just the name
    expect(result).toContain('trait Shape');
    expect(result).toContain('Shape.scala');
    // Must NOT contain error messages
    expect(result).not.toContain('No symbol found');
  });

  it('"Circle" should return case class definition', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toContain('case class Circle');
    expect(result).toContain('Circle.scala');
    expect(result).not.toContain('No symbol found');
  });

  it('"Monad" should return definition from cats library', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Monad' });
    // Must return actual source/decompiled code, not an error
    expect(result).not.toContain('No symbol found');
    // Must contain something that looks like source code (line numbers)
    expect(result).toMatch(/\d+\|/); // line number format: "  42|..."
    // Should mention Monad somewhere in the source
    expect(result).toMatch(/Monad/);
  });

  it('"ZIO" should return definition from zio library', async () => {
    const result = await tools.callTool('definition', { symbolName: 'ZIO' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/\d+\|/);
    expect(result).toMatch(/ZIO/);
  });

  it('"io.circe.Json" (nonexistent dep) should fail gracefully', async () => {
    const result = await tools.callTool('definition', { symbolName: 'io.circe.Json' });
    // circe is not a dependency of the fixture project
    expect(result).toContain('No symbol found');
  });

  it('"cats.Monad" FQN should return Monad from cats specifically', async () => {
    const result = await tools.callTool('definition', { symbolName: 'cats.Monad' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/Monad/);
    expect(result).toMatch(/\d+\|/);
  });

  it('"zio.ZIO" FQN should return ZIO from zio specifically', async () => {
    const result = await tools.callTool('definition', { symbolName: 'zio.ZIO' });
    expect(result).not.toContain('No symbol found');
    expect(result).toMatch(/ZIO/);
    expect(result).toMatch(/\d+\|/);
  });

  it('"example.Shape" FQN should return Shape from project', async () => {
    const result = await tools.callTool('definition', { symbolName: 'example.Shape' });
    expect(result).toContain('trait Shape');
    expect(result).toContain('Shape.scala');
    expect(result).not.toContain('No symbol found');
  });

  it('"ZzNonExistent" should report no symbol found', async () => {
    const result = await tools.callTool('definition', { symbolName: 'ZzNonExistent' });
    expect(result).toContain('No symbol found');
  });
});

// ── definition via filePath+line+column ─────────────────────────────

describe('definition via position', () => {
  it('Monad import should navigate to cats.Monad definition', async () => {
    // ExternalDeps.scala line 3: "import cats.Monad"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13, // on "Monad"
    });
    expect(result).not.toContain('No definition found');
    expect(result).toMatch(/Monad/);
    expect(result).toMatch(/\d+\|/);
  });

  it('ZIO import should navigate to zio.ZIO definition', async () => {
    // ExternalDeps.scala line 5: "import zio.{ZIO, Task}"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 5,
      column: 13, // on "ZIO"
    });
    expect(result).toMatch(/ZIO/);
    expect(result).toMatch(/\d+\|/);
  });

  it('Shape usage should navigate to Shape trait definition', async () => {
    // ExternalDeps.scala line 17: "val shapes: List[Shape]"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 17,
      column: 28, // on "Shape"
    });
    expect(result).toContain('trait Shape');
    expect(result).toContain('Shape.scala');
  });
});

// ── references ──────────────────────────────────────────────────────

describe('references', () => {
  it('"Shape" should find references across multiple files', async () => {
    const result = await tools.callTool('references', { symbolName: 'Shape' });
    expect(result).not.toContain('No references found');
    expect(result).not.toContain('No symbol found');
    // Shape is referenced in Circle.scala, Rectangle.scala, CustomShape.scala,
    // ShapeService.scala, ExternalDeps.scala
    expect(result).toContain('.scala');
    // Check we found multiple files
    const fileHeaders = result.match(/---\n.*\.scala/g);
    expect(fileHeaders).not.toBeNull();
    expect(fileHeaders!.length).toBeGreaterThanOrEqual(3);
  });

  it('Monad reference from import should find project usages', async () => {
    const result = await tools.callTool('references', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13, // "Monad" import
    });
    expect(result).not.toContain('No references found');
    expect(result).toContain('ExternalDeps.scala');
  });

  it('ZIO reference from import should find project usages', async () => {
    const result = await tools.callTool('references', {
      filePath: FIXTURES.externalDeps,
      line: 5,
      column: 13, // "ZIO" import
    });
    expect(result).not.toContain('No references found');
    expect(result).toContain('ExternalDeps.scala');
  });

  it('"cats.Monad" FQN should find references in project', async () => {
    const result = await tools.callTool('references', { symbolName: 'cats.Monad' });
    expect(result).not.toContain('No symbol found');
    // Should find at least the import and usage in ExternalDeps.scala
    expect(result).toContain('ExternalDeps.scala');
  });

  it('"zio.ZIO" FQN should find references in project', async () => {
    const result = await tools.callTool('references', { symbolName: 'zio.ZIO' });
    expect(result).not.toContain('No symbol found');
    expect(result).toContain('ExternalDeps.scala');
  });

  it('"ZzNonExistent" should report no symbol found', async () => {
    const result = await tools.callTool('references', { symbolName: 'ZzNonExistent' });
    expect(result).toContain('No symbol found');
  });
});

// ── implementations ─────────────────────────────────────────────────

describe('implementations', () => {
  it('"Shape" should find Circle and Rectangle implementations', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'Shape' });
    expect(result).not.toContain('No implementations found');
    expect(result).not.toContain('No symbol found');
    expect(result).toContain('Circle');
    expect(result).toContain('Rectangle');
  });

  it('"example.Shape" FQN should also find implementations', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'example.Shape' });
    expect(result).not.toContain('No implementations found');
    expect(result).not.toContain('No symbol found');
    expect(result).toContain('Circle');
    expect(result).toContain('Rectangle');
  });

  it('implementation results should include source code', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'Shape' });
    expect(result).toContain('override def area');
  });
});

// ── match quality: exact vs suffix ──────────────────────────────────

describe('match quality', () => {
  it('"Shape" exact match should take priority over "CustomShape" suffix', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Shape' });
    // Should show Shape trait definition, not CustomShape
    expect(result).toContain('trait Shape');
    expect(result).toContain('Shape.scala');
    // CustomShape is a suffix match — should be filtered out when exact exists
    expect(result).not.toContain('class CustomShape');
  });

  it('"Circle" exact match should show case class definition', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toContain('case class Circle');
    expect(result).toContain('Circle.scala');
  });
});

// ── cached source handling ──────────────────────────────────────────

describe('cached source files', () => {
  const cacheDir = path.join(os.homedir(), '.cache', 'intellij-scala-lsp', 'sources');

  it('external definition should create cached .scala files (not .tasty or .class)', async () => {
    // Trigger a definition lookup for an external symbol
    await tools.callTool('definition', { symbolName: 'Monad' });

    if (fs.existsSync(cacheDir)) {
      const findBinaryFiles = (dir: string): string[] => {
        const results: string[] = [];
        try {
          const entries = fs.readdirSync(dir, { withFileTypes: true });
          for (const entry of entries) {
            const fullPath = path.join(dir, entry.name);
            if (entry.isDirectory()) results.push(...findBinaryFiles(fullPath));
            else if (entry.name.endsWith('.tasty') || entry.name.endsWith('.class'))
              results.push(fullPath);
          }
        } catch { /* ignore */ }
        return results;
      };
      const binaryFiles = findBinaryFiles(cacheDir);
      expect(binaryFiles).toHaveLength(0);
    }
  });
});

// ── hover ───────────────────────────────────────────────────────────

describe('hover', () => {
  it('should show type info for external type usage', async () => {
    // ExternalDeps.scala line 14: greetTask method
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.externalDeps,
      line: 14,
      column: 7, // on "greetTask"
    });
    expect(result).toMatch(/Task|ZIO|String/);
  });

  it('should show type info for CustomShape', async () => {
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.customShape,
      line: 4,
      column: 7, // on "CustomShape"
    });
    expect(result).toMatch(/CustomShape|Shape/);
  });
});
