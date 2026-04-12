/**
 * Multi-session integration tests.
 *
 * Verifies that multiple MCP clients can connect to the same daemon
 * with different project roots and each session is scoped correctly.
 *
 * Prerequisites:
 *   1. Compile both fixtures:
 *      cd mcp-server/test/fixtures && sbt compile
 *      cd mcp-server/test/fixtures2 && sbt compile
 *   2. Import both:
 *      intellij-scala-lsp --import mcp-server/test/fixtures
 *      intellij-scala-lsp --import mcp-server/test/fixtures2
 *   3. Start daemon with both:
 *      intellij-scala-lsp --daemon mcp-server/test/fixtures mcp-server/test/fixtures2
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createSession, TestSession } from './setup.js';
import * as path from 'path';

const MCP_ROOT = path.resolve(__dirname, '..');
const FIXTURE_DIR_1 = path.join(MCP_ROOT, 'test', 'fixtures');
const FIXTURE_DIR_2 = path.join(MCP_ROOT, 'test', 'fixtures2');

let session1: TestSession;
let session2: TestSession;

beforeAll(async () => {
  // Create sessions sequentially to avoid racing on daemon project initialization
  session1 = await createSession(FIXTURE_DIR_1);
  session2 = await createSession(FIXTURE_DIR_2);
}, 120_000);

afterAll(async () => {
  await Promise.all([
    session1?.teardown(),
    session2?.teardown(),
  ]);
});

describe('multi-session: project scoping', () => {
  it('session1 should find Circle from fixtures', async () => {
    const result = await session1.tools.callTool('workspace_symbols', { query: 'Circle' });
    expect(result).toContain('Circle');
    expect(result).not.toContain('No symbols found');
  });

  it('session2 should find UniqueClass from fixtures2', async () => {
    const result = await session2.tools.callTool('workspace_symbols', { query: 'UniqueClass' });
    expect(result).toContain('UniqueClass');
    expect(result).not.toContain('No symbols found');
  });

  it('session1 definition of Circle should return source', async () => {
    const result = await session1.tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toContain('case class Circle');
    expect(result).toMatch(/\d+\|/);
  });

  it('session2 definition of UniqueClass should return source', async () => {
    const result = await session2.tools.callTool('definition', { symbolName: 'UniqueClass' });
    expect(result).toContain('class UniqueClass');
    expect(result).toMatch(/\d+\|/);
  });
});

describe('multi-session: independence', () => {
  it('both sessions can query workspace_symbols concurrently', async () => {
    const [r1, r2] = await Promise.all([
      session1.tools.callTool('workspace_symbols', { query: 'Shape' }),
      session2.tools.callTool('workspace_symbols', { query: 'UniqueClass' }),
    ]);
    expect(r1).toContain('Shape');
    expect(r2).toContain('UniqueClass');
  });

  it('both sessions can call definition concurrently', async () => {
    const [r1, r2] = await Promise.all([
      session1.tools.callTool('definition', { symbolName: 'Shape' }),
      session2.tools.callTool('definition', { symbolName: 'UniqueClass' }),
    ]);
    expect(r1).toContain('trait Shape');
    expect(r2).toContain('class UniqueClass');
  });
});

describe('multi-session: isolation', () => {
  it('session1 should NOT find UniqueClass (fixtures2-only symbol)', async () => {
    const result = await session1.tools.callTool('workspace_symbols', { query: 'UniqueClass' });
    // Session1 (fixtures) shouldn't find fixtures2's UniqueClass, but may find fuzzy matches from external libs
    expect(result).not.toContain('class UniqueClass in session2');
    expect(result).not.toMatch(/fixtures2/);
  });

  it('session2 should NOT find Circle (fixtures-only symbol)', async () => {
    const result = await session2.tools.callTool('workspace_symbols', { query: 'Circle' });
    // Session2 (fixtures2) shouldn't find fixtures' Circle, but may find fuzzy matches from external libs
    expect(result).not.toContain('case class Circle');
    expect(result).not.toMatch(/fixtures\/.*Circle/);
  });

  it('session1 definition of UniqueClass should fail', async () => {
    const result = await session1.tools.callTool('definition', { symbolName: 'UniqueClass' });
    expect(result).toContain('No symbol found');
  });

  it('session2 definition of Circle should fail', async () => {
    const result = await session2.tools.callTool('definition', { symbolName: 'Circle' });
    expect(result).toContain('No symbol found');
  });
});
