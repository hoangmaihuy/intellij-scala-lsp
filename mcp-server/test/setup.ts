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
import { DiagnosticsCache } from '../src/diagnostics-cache.js';
import { SymbolResolver } from '../src/symbol-resolver.js';
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
};

let lsp: LspClient;
let fileManager: FileManager;
let toolRunner: TestToolRunner;

function getDaemonPort(): number {
  const portFile = path.join(os.homedir(), '.cache', 'intellij-scala-lsp', 'daemon.port');
  if (fs.existsSync(portFile)) {
    const port = parseInt(fs.readFileSync(portFile, 'utf-8').trim(), 10);
    if (!isNaN(port)) return port;
  }
  return parseInt(process.env.LSP_PORT || '5007', 10);
}

export async function setupTestEnvironment(): Promise<TestToolRunner> {
  const port = getDaemonPort();
  lsp = new LspClient();
  await lsp.connect(port);

  fileManager = new FileManager(lsp);
  const diagnostics = new DiagnosticsCache(lsp);

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

  const rootUri = pathToUri(FIXTURE_DIR);
  await lsp.initialize(rootUri);

  const symbolResolver = new SymbolResolver(lsp, fileManager);

  toolRunner = new TestToolRunner();
  registerTools(
    toolRunner as any,
    lsp,
    fileManager,
    diagnostics,
    symbolResolver,
  );

  // Give the daemon time to finish analysis after session connects
  await new Promise(r => setTimeout(r, 3000));

  return toolRunner;
}

export async function teardownTestEnvironment(): Promise<void> {
  if (fileManager) fileManager.closeAll();
  if (lsp) await lsp.shutdown();
}

export { FIXTURE_DIR };
