import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as path from 'path';

// Track instances for assertions
let lspInstances: any[] = [];
let fileManagerInstances: any[] = [];
let symbolResolverInstances: any[] = [];

// Shared connect behavior — override in tests to inject failures
let connectBehavior: () => Promise<void> = async () => {};

vi.mock('../src/lsp-client.js', () => ({
  LspClient: class MockLspClient {
    connect = vi.fn().mockImplementation(() => connectBehavior());
    initialize = vi.fn().mockResolvedValue({});
    shutdown = vi.fn().mockResolvedValue(undefined);
    close = vi.fn();
    onRequest = vi.fn();
    onNotification = vi.fn();
    notify = vi.fn();
    request = vi.fn();
    constructor() { lspInstances.push(this); }
  },
}));

vi.mock('../src/file-manager.js', () => ({
  FileManager: class MockFileManager {
    ensureOpen = vi.fn();
    closeAll = vi.fn();
    notifySaved = vi.fn();
    constructor() { fileManagerInstances.push(this); }
  },
}));

vi.mock('../src/symbol-resolver.js', () => ({
  SymbolResolver: class MockSymbolResolver {
    resolve = vi.fn();
    constructor() { symbolResolverInstances.push(this); }
  },
}));

vi.mock('../src/workspace-edit.js', () => ({
  applyWorkspaceEdit: vi.fn().mockReturnValue([]),
}));

vi.mock('../src/logger.js', () => ({
  logger: {
    info: vi.fn(),
    debug: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  },
}));

import { SessionManager } from '../src/session-manager.js';

describe('SessionManager', () => {
  beforeEach(() => {
    lspInstances = [];
    fileManagerInstances = [];
    symbolResolverInstances = [];
    connectBehavior = async () => {};
  });

  it('creates a new session on first getSession call', async () => {
    const sm = new SessionManager(5007);
    const session = await sm.getSession('/project/a');

    expect(lspInstances).toHaveLength(1);
    expect(lspInstances[0].connect).toHaveBeenCalledWith(5007);
    expect(lspInstances[0].initialize).toHaveBeenCalledWith(`file://${path.resolve('/project/a')}`);
    expect(fileManagerInstances).toHaveLength(1);
    expect(symbolResolverInstances).toHaveLength(1);
    expect(session.lsp).toBeDefined();
    expect(session.fileManager).toBeDefined();
    expect(session.symbolResolver).toBeDefined();
  });

  it('reuses existing session for same project path', async () => {
    const sm = new SessionManager(5007);
    const session1 = await sm.getSession('/project/a');
    const session2 = await sm.getSession('/project/a');

    expect(session1).toBe(session2);
    expect(lspInstances).toHaveLength(1);
  });

  it('normalizes paths so equivalent paths share a session', async () => {
    const sm = new SessionManager(5007);
    const session1 = await sm.getSession('/project/a');
    const session2 = await sm.getSession('/project/a/');
    const session3 = await sm.getSession('/project/a/./');

    expect(session1).toBe(session2);
    expect(session1).toBe(session3);
    expect(lspInstances).toHaveLength(1);
  });

  it('creates separate sessions for different projects', async () => {
    const sm = new SessionManager(5007);
    const sessionA = await sm.getSession('/project/a');
    const sessionB = await sm.getSession('/project/b');

    expect(sessionA).not.toBe(sessionB);
    expect(lspInstances).toHaveLength(2);
    expect(lspInstances[0].connect).toHaveBeenCalledWith(5007);
    expect(lspInstances[1].connect).toHaveBeenCalledWith(5007);
  });

  it('deduplicates concurrent connections to the same project', async () => {
    const sm = new SessionManager(5007);
    const [session1, session2] = await Promise.all([
      sm.getSession('/project/a'),
      sm.getSession('/project/a'),
    ]);

    expect(session1).toBe(session2);
    expect(lspInstances).toHaveLength(1);
  });

  it('registers request handlers on each session', async () => {
    const sm = new SessionManager(5007);
    await sm.getSession('/project/a');

    const lsp = lspInstances[0];
    const requestCalls = lsp.onRequest.mock.calls.map((c: any[]) => c[0]);
    expect(requestCalls).toContain('workspace/applyEdit');
    expect(requestCalls).toContain('workspace/configuration');
    expect(requestCalls).toContain('client/registerCapability');
  });

  it('registers notification handlers on each session', async () => {
    const sm = new SessionManager(5007);
    await sm.getSession('/project/a');

    const lsp = lspInstances[0];
    const notifCalls = lsp.onNotification.mock.calls.map((c: any[]) => c[0]);
    expect(notifCalls).toContain('window/showMessage');
    expect(notifCalls).toContain('window/logMessage');
  });

  it('closeAll shuts down all sessions', async () => {
    const sm = new SessionManager(5007);
    await sm.getSession('/project/a');
    await sm.getSession('/project/b');

    await sm.closeAll();

    expect(lspInstances[0].shutdown).toHaveBeenCalledTimes(1);
    expect(lspInstances[1].shutdown).toHaveBeenCalledTimes(1);
    expect(fileManagerInstances[0].closeAll).toHaveBeenCalledTimes(1);
    expect(fileManagerInstances[1].closeAll).toHaveBeenCalledTimes(1);
  });

  it('closeAll clears sessions so next getSession creates fresh ones', async () => {
    const sm = new SessionManager(5007);
    await sm.getSession('/project/a');
    await sm.closeAll();

    const countBefore = lspInstances.length;
    await sm.getSession('/project/a');
    expect(lspInstances).toHaveLength(countBefore + 1);
  });

  it('handles connection failure and allows retry', async () => {
    let callCount = 0;
    connectBehavior = async () => {
      callCount++;
      if (callCount === 1) throw new Error('Connection refused');
    };

    const sm = new SessionManager(5007);
    await expect(sm.getSession('/project/fail')).rejects.toThrow('Connection refused');

    // After failure, the connecting map should be cleaned up so retry works
    const session = await sm.getSession('/project/fail');
    expect(session).toBeDefined();
    expect(callCount).toBe(2);
  });
});
