import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { execSync, spawn } from 'child_process';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { LspClient } from './lsp-client.js';
import { FileManager } from './file-manager.js';
import { SymbolResolver } from './symbol-resolver.js';
import { applyWorkspaceEdit } from './workspace-edit.js';
import { registerTools } from './tools/register.js';
import { logger } from './logger.js';
import { pathToUri } from './utils.js';
import { WorkspaceEdit } from 'vscode-languageserver-protocol';

const CACHE_DIR = path.join(os.homedir(), '.cache', 'intellij-scala-lsp');
const PORT_FILE = path.join(CACHE_DIR, 'daemon.port');

async function getDaemonPort(): Promise<number> {
  if (fs.existsSync(PORT_FILE)) {
    const port = parseInt(fs.readFileSync(PORT_FILE, 'utf-8').trim(), 10);
    if (!isNaN(port)) return port;
  }
  return 5007;
}

async function waitForPortFile(timeoutMs: number): Promise<number> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fs.existsSync(PORT_FILE)) {
      const port = parseInt(fs.readFileSync(PORT_FILE, 'utf-8').trim(), 10);
      if (!isNaN(port)) return port;
    }
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error(`Daemon did not start within ${timeoutMs / 1000}s`);
}

async function ensureDaemonRunning(): Promise<number> {
  const port = await getDaemonPort();
  const lsp = new LspClient();
  try {
    await lsp.connect(port);
    lsp.close();
    logger.info(`Daemon already running on port ${port}`);
    return port;
  } catch {
    logger.info('Daemon not running, starting...');
  }

  let launcherCmd = 'intellij-scala-lsp';
  try {
    execSync('which intellij-scala-lsp', { stdio: 'ignore' });
  } catch {
    const repoRoot = path.resolve(__dirname, '..', '..');
    const localLauncher = path.join(repoRoot, 'launcher', 'intellij-scala-lsp');
    if (fs.existsSync(localLauncher)) {
      launcherCmd = localLauncher;
    } else {
      throw new Error('intellij-scala-lsp launcher not found on PATH or in repo');
    }
  }

  const child = spawn(launcherCmd, ['--daemon'], {
    detached: true,
    stdio: 'ignore',
  });
  child.unref();

  return waitForPortFile(60_000);
}

async function main(): Promise<void> {
  const rootUri = pathToUri(process.cwd());
  logger.info(`Starting MCP server for workspace: ${process.cwd()}`);

  const port = await ensureDaemonRunning();

  const lsp = new LspClient();
  await lsp.connect(port);

  const fileManager = new FileManager(lsp);
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
  lsp.onNotification('window/showMessage', (params) => {
    const p = params as { type: number; message: string };
    logger.info(`[LSP] ${p.message}`);
  });
  lsp.onNotification('window/logMessage', (params) => {
    const p = params as { type: number; message: string };
    logger.debug(`[LSP] ${p.message}`);
  });

  await lsp.initialize(rootUri);

  const symbolResolver = new SymbolResolver(lsp, fileManager);

  const mcp = new McpServer({
    name: 'intellij-scala-lsp',
    version: '0.1.0',
  });

  registerTools(mcp, lsp, fileManager, symbolResolver);

  const transport = new StdioServerTransport();
  await mcp.connect(transport);
  logger.info('MCP server ready');

  const shutdown = async () => {
    logger.info('Shutting down...');
    fileManager.closeAll();
    await lsp.shutdown();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  logger.error(`Fatal: ${err}`);
  process.exit(1);
});
