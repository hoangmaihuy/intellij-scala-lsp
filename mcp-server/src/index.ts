import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { execSync, spawn } from 'child_process';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { LspClient } from './lsp-client.js';
import { SessionManager } from './session-manager.js';
import { registerTools } from './tools/register.js';
import { logger } from './logger.js';

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
  const projectRoot = process.env.CLAUDE_WORKING_DIR || process.cwd();
  logger.info(`Starting MCP server for workspace: ${projectRoot}`);

  const port = await ensureDaemonRunning();

  const sessionManager = new SessionManager(port);

  // Pre-connect primary project
  await sessionManager.getSession(projectRoot);

  const mcp = new McpServer({
    name: 'intellij-scala-lsp',
    version: '0.1.0',
  });

  registerTools(mcp, sessionManager);

  const transport = new StdioServerTransport();
  await mcp.connect(transport);
  logger.info('MCP server ready');

  const shutdown = async () => {
    logger.info('Shutting down...');
    await sessionManager.closeAll();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  logger.error(`Fatal: ${err}`);
  process.exit(1);
});
