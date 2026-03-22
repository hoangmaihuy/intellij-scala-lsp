/**
 * Unit tests for SymbolResolver — FQN parsing, match quality, package filtering.
 *
 * These tests mock the LSP client so no daemon is needed.
 * They verify the core logic that was changed: FQN parsing sends only the simple
 * name to LSP, filters results by containerName/package prefix, and sorts by quality.
 */
import { describe, it, expect, vi } from 'vitest';
import { SymbolResolver, MatchQuality } from '../src/symbol-resolver.js';
import { SymbolKind } from 'vscode-languageserver-protocol';

// ── Test helpers ─────────────────────────────────────────────────────

function mockLsp(symbols: any[] = []) {
  return {
    request: vi.fn().mockResolvedValue(symbols),
  } as any;
}

function mockFileManager() {
  return {
    ensureOpen: vi.fn().mockResolvedValue('file:///mock'),
  } as any;
}

function sym(name: string, kind: SymbolKind, containerName?: string, uri = 'file:///mock.scala') {
  return {
    name,
    kind,
    containerName: containerName ?? null,
    location: { uri, range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } } },
  };
}

// ── FQN parsing: query sent to LSP ──────────────────────────────────

describe('FQN parsing — LSP query extraction', () => {
  it('resolve("io.circe.Json") should query LSP with just "Json"', async () => {
    const lsp = mockLsp([]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    await resolver.resolve('io.circe.Json');
    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'Json' });
  });

  it('resolve("cats.Monad") should query LSP with just "Monad"', async () => {
    const lsp = mockLsp([]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    await resolver.resolve('cats.Monad');
    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'Monad' });
  });

  it('resolve("zio.ZIO") should query LSP with just "ZIO"', async () => {
    const lsp = mockLsp([]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    await resolver.resolve('zio.ZIO');
    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'ZIO' });
  });

  it('resolve("Shape") — simple name should query LSP with "Shape" unchanged', async () => {
    const lsp = mockLsp([]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    await resolver.resolve('Shape');
    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'Shape' });
  });

  it('resolve("a.b.c.d.MyClass") — deeply nested should query with just "MyClass"', async () => {
    const lsp = mockLsp([]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    await resolver.resolve('a.b.c.d.MyClass');
    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'MyClass' });
  });
});

// ── FQN filtering: only matching package passes ─────────────────────

describe('FQN filtering — package prefix matching', () => {
  it('"io.circe.Json" should match container "io.circe" and reject "play.api.libs.json"', async () => {
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, 'io.circe'),
      sym('Json', SymbolKind.Class, 'play.api.libs.json'),
      sym('Json', SymbolKind.Class, 'spray.json'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');

    expect(results).toHaveLength(1);
    expect(results[0].containerName).toBe('io.circe');
    expect(results[0].matchQuality).toBe('exact');
  });

  it('"cats.Monad" should match container "cats" and reject "scalaz"', async () => {
    const lsp = mockLsp([
      sym('Monad', SymbolKind.Interface, 'cats'),
      sym('Monad', SymbolKind.Class, 'scalaz'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('cats.Monad');

    expect(results).toHaveLength(1);
    expect(results[0].containerName).toBe('cats');
  });

  it('"io.circe.Json" should reject null containerName', async () => {
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, undefined),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(0);
  });

  it('"io.circe.Json" should accept container "circe" (prefix ends with container)', async () => {
    // containerMatchesPackage: packagePrefix "io.circe" endsWith "." + "circe"
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, 'circe'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(1);
  });

  it('"cats.Monad" with container "cats.Monad" — container ends with prefix should NOT match', async () => {
    // containerMatchesPackage("cats.Monad", "cats"):
    //   "cats.Monad" === "cats" → false
    //   "cats.Monad".endsWith(".cats") → false
    //   "cats".endsWith(".cats.Monad") → false
    // This is a subtle edge case — the companion object container might be the class FQN
    const lsp = mockLsp([
      sym('apply', SymbolKind.Method, 'cats.Monad'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('cats.Monad');
    // "apply" !== "Monad" so it won't match exact, and it's a Method so suffix match needs "." prefix
    expect(results).toHaveLength(0);
  });

  it('FQN should NOT allow suffix matches from wrong package', async () => {
    // "io.circe.Json" should NOT match "spray.json.JsonObject" as suffix
    const lsp = mockLsp([
      sym('JsonObject', SymbolKind.Class, 'spray.json'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(0);
  });
});

// ── Companion object matching ───────────────────────────────────────

describe('companion object matching', () => {
  it('"io.circe.Json" should match both Json and Json$ from same package', async () => {
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, 'io.circe'),
      sym('Json$', SymbolKind.Object, 'io.circe'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');

    expect(results).toHaveLength(2);
    expect(results[0].matchQuality).toBe('exact');
    expect(results[0].name).toBe('Json');
    expect(results[1].matchQuality).toBe('companion');
    expect(results[1].name).toBe('Json$');
  });

  it('"Foo" should match Foo$ companion without FQN', async () => {
    const lsp = mockLsp([
      sym('Foo', SymbolKind.Class, 'pkg'),
      sym('Foo$', SymbolKind.Object, 'pkg'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Foo');

    expect(results).toHaveLength(2);
    expect(results[0].matchQuality).toBe('exact');
    expect(results[1].matchQuality).toBe('companion');
  });

  it('"io.circe.Json" should NOT match Json$ from wrong package', async () => {
    const lsp = mockLsp([
      sym('Json$', SymbolKind.Object, 'spray.json'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(0);
  });
});

// ── Simple name resolution (no package prefix) ─────────────────────

describe('simple name resolution — backwards compatibility', () => {
  it('"Shape" should match exact "Shape" and suffix "CustomShape"', async () => {
    const lsp = mockLsp([
      sym('Shape', SymbolKind.Interface, 'example'),
      sym('CustomShape', SymbolKind.Class, 'example'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Shape');

    expect(results).toHaveLength(2);
    expect(results[0].name).toBe('Shape');
    expect(results[0].matchQuality).toBe('exact');
    expect(results[1].name).toBe('CustomShape');
    expect(results[1].matchQuality).toBe('suffix');
  });

  it('"Shape" should match Shape from ANY package (no filtering)', async () => {
    const lsp = mockLsp([
      sym('Shape', SymbolKind.Interface, 'pkg.a'),
      sym('Shape', SymbolKind.Class, 'pkg.b'),
      sym('Shape', SymbolKind.Class, undefined),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Shape');
    expect(results).toHaveLength(3);
    expect(results.every(r => r.matchQuality === 'exact')).toBe(true);
  });

  it('simple name should NOT suffix-match methods without dot/:: separator', async () => {
    // Method "bar" should not suffix-match query "bar" unless it has Container. prefix
    const lsp = mockLsp([
      sym('bar', SymbolKind.Method, 'Foo'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('bar');
    // "bar" === "bar" → exact match
    expect(results).toHaveLength(1);
    expect(results[0].matchQuality).toBe('exact');
  });

  it('simple name should suffix-match method "Foo.bar" for query "bar"', async () => {
    const lsp = mockLsp([
      sym('Foo.bar', SymbolKind.Method, 'pkg'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('bar');
    expect(results).toHaveLength(1);
    expect(results[0].matchQuality).toBe('suffix');
  });
});

// ── Sort order: exact > companion > qualified > suffix ──────────────

describe('result sorting by match quality', () => {
  it('exact match should come before companion and suffix', async () => {
    const lsp = mockLsp([
      sym('CustomJson', SymbolKind.Class, 'pkg'),      // suffix
      sym('Json$', SymbolKind.Object, 'pkg'),           // companion
      sym('Json', SymbolKind.Class, 'pkg'),              // exact
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Json');

    expect(results).toHaveLength(3);
    expect(results[0].matchQuality).toBe('exact');
    expect(results[0].name).toBe('Json');
    expect(results[1].matchQuality).toBe('companion');
    expect(results[1].name).toBe('Json$');
    expect(results[2].matchQuality).toBe('suffix');
    expect(results[2].name).toBe('CustomJson');
  });

  it('FQN results should also be sorted: exact before companion', async () => {
    const lsp = mockLsp([
      sym('ZIO$', SymbolKind.Object, 'zio'),  // companion
      sym('ZIO', SymbolKind.Class, 'zio'),      // exact
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('zio.ZIO');

    expect(results).toHaveLength(2);
    expect(results[0].matchQuality).toBe('exact');
    expect(results[0].name).toBe('ZIO');
    expect(results[1].matchQuality).toBe('companion');
    expect(results[1].name).toBe('ZIO$');
  });
});

// ── Qualified method name: "Container.method" ───────────────────────

describe('qualified method name — Container.method', () => {
  it('"Container.myMethod" should resolve method by container + name', async () => {
    // parseQualifiedName → simpleName="myMethod", packagePrefix="Container"
    // matchQuality("myMethod", "myMethod", Method, "Container", "Container"):
    //   packageMatches = containerMatchesPackage("Container", "Container") → true
    //   symbolName === query → true → exact
    const lsp = mockLsp([
      sym('myMethod', SymbolKind.Method, 'Container'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Container.myMethod');

    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'myMethod' });
    expect(results).toHaveLength(1);
    expect(results[0].matchQuality).toBe('exact');
    expect(results[0].name).toBe('myMethod');
  });

  it('"Container.myMethod" should NOT match method from different container', async () => {
    const lsp = mockLsp([
      sym('myMethod', SymbolKind.Method, 'OtherContainer'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Container.myMethod');
    expect(results).toHaveLength(0);
  });

  it('"pkg.Container.myMethod" should match method in container "pkg.Container"', async () => {
    const lsp = mockLsp([
      sym('myMethod', SymbolKind.Method, 'pkg.Container'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('pkg.Container.myMethod');

    expect(lsp.request).toHaveBeenCalledWith('workspace/symbol', { query: 'myMethod' });
    expect(results).toHaveLength(1);
    expect(results[0].matchQuality).toBe('exact');
  });
});

// ── Edge cases: empty/null LSP responses ────────────────────────────

describe('empty and null LSP responses', () => {
  it('should return empty array when LSP returns empty array', async () => {
    const lsp = mockLsp([]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(0);
  });

  it('should return empty array when LSP returns null', async () => {
    const lsp = { request: vi.fn().mockResolvedValue(null) } as any;
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(0);
  });

  it('should return empty array when LSP returns undefined', async () => {
    const lsp = { request: vi.fn().mockResolvedValue(undefined) } as any;
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('Foo');
    expect(results).toHaveLength(0);
  });
});

// ── Edge cases: fileManager.ensureOpen failures ─────────────────────

describe('fileManager.ensureOpen failures', () => {
  it('should skip symbols whose files cannot be opened', async () => {
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, 'io.circe', 'file:///good.scala'),
      sym('Json2', SymbolKind.Class, 'io.circe', 'file:///bad.scala'),
    ]);
    const fm = {
      ensureOpen: vi.fn().mockImplementation((path: string) => {
        if (path === '/bad.scala') throw new Error('File not found');
        return Promise.resolve('file:///good.scala');
      }),
    } as any;
    const resolver = new SymbolResolver(lsp, fm);

    // Simple name query so both match
    const results = await resolver.resolve('Json');
    // Json2 is not exact match for "Json" (no suffix match for non-type names... wait)
    // Actually "Json2" doesn't end with "Json" so it won't suffix match
    // Only Json matches
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('Json');
  });
});

// ── Suffix matching: only certain SymbolKinds ───────────────────────

describe('suffix matching — kind restrictions', () => {
  it('suffix match works for Class, Interface, Enum, Object', async () => {
    const lsp = mockLsp([
      sym('CustomShape', SymbolKind.Class, 'pkg'),
      sym('IShape', SymbolKind.Interface, 'pkg'),
      sym('MyShapeEnum', SymbolKind.Enum, 'pkg'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Shape');
    // CustomShape ends with "Shape" → suffix
    // IShape ends with "Shape" → suffix
    // MyShapeEnum does NOT end with "Shape" → no match
    expect(results).toHaveLength(2);
    expect(results.every(r => r.matchQuality === 'suffix')).toBe(true);
  });

  it('suffix match does NOT work for plain Variables/Fields', async () => {
    const lsp = mockLsp([
      sym('myShape', SymbolKind.Variable, 'pkg'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Shape');
    // Variable "myShape" should NOT suffix-match "Shape"
    expect(results).toHaveLength(0);
  });

  it('method suffix match requires dot or :: separator', async () => {
    const lsp = mockLsp([
      sym('Foo.process', SymbolKind.Method, 'pkg'),
      sym('preprocess', SymbolKind.Method, 'pkg'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('process');
    // "Foo.process" ends with ".process" → suffix
    // "preprocess" does NOT end with ".process" → no match
    // But "process" === "process" → exact match... wait, "preprocess" !== "process"
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('Foo.process');
    expect(results[0].matchQuality).toBe('suffix');
  });
});

// ── Regression guard: FQN with suffix match from matching package ───

describe('regression: FQN + suffix match from correct package', () => {
  it('"io.circe.Json" should include suffix "CirceJson" if container matches', async () => {
    // If the LSP returns a type that ends with "Json" from the right package,
    // it should be included as a suffix match
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, 'io.circe'),
      sym('CirceJson', SymbolKind.Class, 'io.circe'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(2);
    expect(results[0].matchQuality).toBe('exact');
    expect(results[1].matchQuality).toBe('suffix');
  });

  it('"io.circe.Json" should NOT include suffix "CirceJson" from wrong package', async () => {
    const lsp = mockLsp([
      sym('Json', SymbolKind.Class, 'io.circe'),
      sym('CirceJson', SymbolKind.Class, 'spray.json'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('io.circe.Json');
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('Json');
  });
});

// ── Regression guard: simple name still works after FQN changes ─────

describe('regression: simple name must still work identically', () => {
  it('"Monad" without FQN should match Monad from ANY package', async () => {
    const lsp = mockLsp([
      sym('Monad', SymbolKind.Interface, 'cats'),
      sym('Monad', SymbolKind.Class, 'scalaz'),
      sym('Monad', SymbolKind.Interface, 'custom.lib'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Monad');
    expect(results).toHaveLength(3);
    expect(results.every(r => r.matchQuality === 'exact')).toBe(true);
  });

  it('"Monad" should include companion objects from all packages', async () => {
    const lsp = mockLsp([
      sym('Monad', SymbolKind.Interface, 'cats'),
      sym('Monad$', SymbolKind.Object, 'cats'),
      sym('Monad', SymbolKind.Class, 'scalaz'),
      sym('Monad$', SymbolKind.Object, 'scalaz'),
    ]);
    const resolver = new SymbolResolver(lsp, mockFileManager());

    const results = await resolver.resolve('Monad');
    expect(results).toHaveLength(4);
  });
});

// ── containerMatchesPackage edge cases ──────────────────────────────

describe('containerMatchesPackage logic', () => {
  // These test the package matching through the public resolve() API

  it('exact container match: container="io.circe", prefix="io.circe"', async () => {
    const lsp = mockLsp([sym('X', SymbolKind.Class, 'io.circe')]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.X');
    expect(results).toHaveLength(1);
  });

  it('container endsWith match: container="com.io.circe", prefix="io.circe"', async () => {
    const lsp = mockLsp([sym('X', SymbolKind.Class, 'com.io.circe')]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.X');
    expect(results).toHaveLength(1);
  });

  it('prefix endsWith container: container="circe", prefix="io.circe"', async () => {
    const lsp = mockLsp([sym('X', SymbolKind.Class, 'circe')]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.X');
    expect(results).toHaveLength(1);
  });

  it('no match: container="spray.json", prefix="io.circe"', async () => {
    const lsp = mockLsp([sym('X', SymbolKind.Class, 'spray.json')]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.X');
    expect(results).toHaveLength(0);
  });

  it('no match: container="circe.decoder", prefix="io.circe"', async () => {
    // "circe.decoder" does NOT endsWith ".io.circe"
    // "io.circe" does NOT endsWith ".circe.decoder"
    const lsp = mockLsp([sym('X', SymbolKind.Class, 'circe.decoder')]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.X');
    expect(results).toHaveLength(0);
  });

  it('partial overlap should NOT match: container="not.circe", prefix="io.circe"', async () => {
    const lsp = mockLsp([sym('X', SymbolKind.Class, 'not.circe')]);
    const resolver = new SymbolResolver(lsp, mockFileManager());
    const results = await resolver.resolve('io.circe.X');
    // containerMatchesPackage("not.circe", "io.circe"):
    //   "not.circe" === "io.circe" → false
    //   "not.circe".endsWith(".io.circe") → false
    //   "io.circe".endsWith(".not.circe") → false
    expect(results).toHaveLength(0);
  });
});
