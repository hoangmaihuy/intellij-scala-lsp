/**
 * Test setup: connects to the LSP daemon, registers tools, exposes callTool().
 *
 * Prerequisites:
 *   1. Import the fixture project: intellij-scala-lsp --import mcp-server/test/fixtures
 *   2. Start the daemon: intellij-scala-lsp --daemon mcp-server/test/fixtures
 */
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { LspClient } from '../src/lsp-client.js';
import { FileManager } from '../src/file-manager.js';
import { SymbolResolver } from '../src/symbol-resolver.js';
import { LspSession, SessionManager } from '../src/session-manager.js';
import { applyWorkspaceEdit } from '../src/workspace-edit.js';
import { registerTools } from '../src/tools/register.js';
import { pathToUri } from '../src/utils.js';
import { TestToolRunner } from './tool-runner.js';
import { WorkspaceEdit } from 'vscode-languageserver-protocol';

const MCP_ROOT = path.resolve(__dirname, '..');
const FIXTURE_DIR = path.join(MCP_ROOT, 'test', 'fixtures');
const SRC = path.join(FIXTURE_DIR, 'src', 'main', 'scala', 'example');

/** Fixture file absolute paths */
export const FIXTURES = {
  shape: path.join(SRC, 'Shape.scala'),
  circle: path.join(SRC, 'Circle.scala'),
  rectangle: path.join(SRC, 'Rectangle.scala'),
  shapeService: path.join(SRC, 'ShapeService.scala'),
  main: path.join(SRC, 'Main.scala'),
  errors: path.join(SRC, 'Errors.scala'),
  customShape: path.join(SRC, 'CustomShape.scala'),
  externalDeps: path.join(SRC, 'ExternalDeps.scala'),
};

export interface TestSession {
  lsp: LspClient;
  fileManager: FileManager;
  symbolResolver: SymbolResolver;
  tools: TestToolRunner;
  teardown: () => Promise<void>;
}

function getDaemonPort(): number {
  const portFile = path.join(os.homedir(), '.cache', 'intellij-scala-lsp', 'daemon.port');
  if (fs.existsSync(portFile)) {
    const port = parseInt(fs.readFileSync(portFile, 'utf-8').trim(), 10);
    if (!isNaN(port)) return port;
  }
  return parseInt(process.env.LSP_PORT || '5007', 10);
}

/**
 * Create an independent LSP session for the given project directory.
 * Each session gets its own LspClient, FileManager, SymbolResolver, and TestToolRunner.
 */
export async function createSession(projectDir: string): Promise<TestSession> {
  const port = getDaemonPort();
  const lsp = new LspClient();
  await lsp.connect(port);

  const fileManager = new FileManager(lsp);

  // Handle server-initiated requests (same as index.ts)
  lsp.onRequest('workspace/applyEdit', async (params: unknown) => {
    const p = params as { edit: WorkspaceEdit };
    const modifiedUris = applyWorkspaceEdit(p.edit);
    for (const uri of modifiedUris) {
      fileManager.notifySaved(uri);
    }
    return { applied: true };
  });
  lsp.onRequest('workspace/configuration', async () => [{}]);
  lsp.onRequest('client/registerCapability', async () => null);
  lsp.onRequest('window/workDoneProgress/create', async () => null);

  // Handle server-initiated notifications
  lsp.onNotification('window/showMessage', () => {});
  lsp.onNotification('window/logMessage', () => {});

  const rootUri = pathToUri(projectDir);
  await lsp.initialize(rootUri);

  const symbolResolver = new SymbolResolver(lsp, fileManager);

  const tools = new TestToolRunner();
  tools.setDefaultProjectPath(projectDir);
  // Create a mock SessionManager that returns the existing session
  const session: LspSession = { lsp, fileManager, symbolResolver };
  const mockSessionManager = {
    getSession: async () => session,
    closeAll: async () => {},
  } as unknown as SessionManager;
  registerTools(tools as any, mockSessionManager);

  // Give the daemon time to finish analysis after session connects
  await new Promise(r => setTimeout(r, 3000));

  return {
    lsp,
    fileManager,
    symbolResolver,
    tools,
    teardown: async () => {
      fileManager.closeAll();
      await lsp.shutdown();
    },
  };
}

export async function setupTestEnvironment(): Promise<TestToolRunner> {
  const session = await createSession(FIXTURE_DIR);
  // Store references for teardown
  _currentSession = session;
  return session.tools;
}

let _currentSession: TestSession | undefined;

export async function teardownTestEnvironment(): Promise<void> {
  if (_currentSession) {
    await _currentSession.teardown();
    _currentSession = undefined;
  }
}

export { FIXTURE_DIR };
