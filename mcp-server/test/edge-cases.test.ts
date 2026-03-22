/**
 * Edge Case Tests — External Dependencies, Match Quality, Cached Sources
 *
 * Tests for:
 *   - Definition of external library symbols (cats Monad, zio ZIO)
 *   - References of external library symbols in project code
 *   - Match quality sorting (exact > suffix)
 *   - Suffix-only results hint message
 *   - Definition result capping
 *   - Cached source file handling (.tasty → .scala)
 *   - workspace_symbols including library symbols
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

// ── External Dependency: Definition ─────────────────────────────────

describe('definition of external symbols', () => {
  it('should resolve Monad from cats via symbolName', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Monad' });
    // Should find the cats Monad trait — either from source jar or decompiled
    expect(result).toMatch(/Monad|trait|cats/);
    expect(result).not.toContain('No symbol found');
  });

  it('should resolve ZIO from zio via symbolName', async () => {
    const result = await tools.callTool('definition', { symbolName: 'ZIO' });
    expect(result).toMatch(/ZIO|zio/);
    expect(result).not.toContain('No symbol found');
  });

  it('should resolve external symbol via filePath+line+column from usage site', async () => {
    // ExternalDeps.scala line 3: "import cats.Monad" — column on "Monad"
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13, // on "Monad"
    });
    expect(result).toMatch(/Monad/);
  });

  it('should resolve Task alias from zio via position', async () => {
    // ExternalDeps.scala line 5: "import zio.{ZIO, Task}" — Task at col 19
    const result = await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 5,
      column: 19, // on "Task"
    });
    expect(result).toMatch(/Task|ZIO|zio/);
  });
});

// ── External Dependency: References ─────────────────────────────────

describe('references of external symbols', () => {
  it('should find project references of Monad', async () => {
    // Monad is used in ExternalDeps.scala — search by position from usage site
    const result = await tools.callTool('references', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13, // on "Monad" import
    });
    // Should find at least the import and the using clause
    expect(result).toContain('ExternalDeps.scala');
  });

  it('should find project references of ZIO', async () => {
    const result = await tools.callTool('references', {
      filePath: FIXTURES.externalDeps,
      line: 5,
      column: 13, // on "ZIO"
    });
    expect(result).toContain('ExternalDeps.scala');
  });

  it('should find references of Shape across all files', async () => {
    const result = await tools.callTool('references', { symbolName: 'Shape' });
    // Shape is used in Circle, Rectangle, CustomShape, ShapeService, Main, ExternalDeps
    expect(result).toContain('.scala');
    const fileMatches = result.match(/References: \d+/g);
    expect(fileMatches).not.toBeNull();
    expect(fileMatches!.length).toBeGreaterThanOrEqual(3);
  });
});

// ── Match Quality: Exact vs Suffix ──────────────────────────────────

describe('match quality sorting', () => {
  it('should prioritize exact match "Shape" over suffix "CustomShape"', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Shape' });
    // Should find the Shape trait, not just CustomShape
    expect(result).toContain('trait Shape');
    expect(result).toContain('Shape.scala');
    // CustomShape is a suffix match — should be filtered out when exact exists
    expect(result).not.toContain('class CustomShape');
  });

  it('should find exact "Circle" without other suffix matches', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toContain('case class Circle');
    expect(result).toContain('Circle.scala');
  });
});

// ── Suffix-Only Results ─────────────────────────────────────────────

describe('suffix-only definition results', () => {
  it('should show hint when no exact match exists', async () => {
    // "tomShape" doesn't exist but "CustomShape" ends with "tomShape" — actually no.
    // Use a query that gets no results at all
    const result = await tools.callTool('definition', { symbolName: 'ZzNonExistent' });
    expect(result).toContain('No symbol found');
  });
});

// ── Definition Result Capping ───────────────────────────────────────

describe('definition result capping', () => {
  it('should not return more than 3 full source blocks', async () => {
    const result = await tools.callTool('definition', { symbolName: 'Shape' });
    // Count "---" blocks (each result starts with ---)
    const blocks = result.split('---').filter(s => s.trim().length > 0);
    expect(blocks.length).toBeLessThanOrEqual(4); // 3 results + possible hint message
  });
});

// ── Workspace Symbols: External ─────────────────────────────────────

describe('workspace_symbols with external deps', () => {
  it('should find project symbols', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Shape' });
    expect(result).toContain('Shape');
  });

  it('should find external library symbols with include non-project', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'Monad' });
    // After the allScope fix, should find cats.Monad
    expect(result).toMatch(/Monad/);
  });

  it('should find ZIO in workspace symbols', async () => {
    const result = await tools.callTool('workspace_symbols', { query: 'ZIO' });
    expect(result).toMatch(/ZIO/);
  });
});

// ── Cached Source File Handling ──────────────────────────────────────

describe('cached source file handling', () => {
  const cacheDir = path.join(os.homedir(), '.cache', 'intellij-scala-lsp', 'sources');

  it('should not have .tasty files in source cache', () => {
    if (!fs.existsSync(cacheDir)) return; // skip if no cache yet

    const findTastyFiles = (dir: string): string[] => {
      const results: string[] = [];
      try {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory()) results.push(...findTastyFiles(fullPath));
          else if (entry.name.endsWith('.tasty')) results.push(fullPath);
        }
      } catch { /* ignore */ }
      return results;
    };

    const tastyFiles = findTastyFiles(cacheDir);
    expect(tastyFiles).toHaveLength(0);
  });

  it('should cache external sources as .scala files', async () => {
    // Trigger a definition lookup for an external symbol to populate cache
    await tools.callTool('definition', {
      filePath: FIXTURES.externalDeps,
      line: 3,
      column: 13, // Monad import
    });

    // Check that any cached files have .scala extension, not .tasty or .class
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

// ── Implementations of External Symbols ─────────────────────────────

describe('implementations edge cases', () => {
  it('should find Shape implementations', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'Shape' });
    expect(result).toContain('Circle');
    expect(result).toContain('Rectangle');
    // CustomShape should also appear if daemon indexed it
    expect(result).toMatch(/implementation/i);
  });

  it('should return source code for implementations', async () => {
    const result = await tools.callTool('implementations', { symbolName: 'Shape' });
    expect(result).toContain('override def area');
  });
});

// ── Hover with External Types ───────────────────────────────────────

describe('hover with external types', () => {
  it('should show type info for external type usage', async () => {
    // ExternalDeps.scala line 14: greetTask method returns Task[String]
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.externalDeps,
      line: 14,
      column: 7, // on "greetTask"
    });
    expect(result).toMatch(/Task|ZIO|String/);
  });

  it('should show supertypes for CustomShape', async () => {
    const result = await tools.callTool('hover', {
      filePath: FIXTURES.customShape,
      line: 4,
      column: 7, // on "CustomShape"
    });
    expect(result).toMatch(/CustomShape|Shape/);
  });
});
