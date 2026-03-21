#!/usr/bin/env node
// End-to-end test for the MCP server
// Spawns the MCP server as a child process and sends MCP tool calls
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { spawn } from 'child_process';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WORKSPACE = '/Users/hoangmei/Work/intellij-scala-lsp';
const TEST_FILE = path.join(WORKSPACE, 'lsp-server/src/intellij/DefinitionProvider.scala');

let pass = 0;
let fail = 0;

function check(name, condition, detail) {
  if (condition) {
    console.log(`  PASS: ${name}`);
    pass++;
  } else {
    console.log(`  FAIL: ${name}`);
    if (detail) console.log(`    ${typeof detail === 'string' ? detail : JSON.stringify(detail).substring(0, 200)}`);
    fail++;
  }
}

async function main() {
  console.log('=== MCP Server End-to-End Tests ===\n');
  console.log(`Workspace: ${WORKSPACE}`);
  console.log(`Test file: ${TEST_FILE}\n`);

  // Start MCP server
  console.log('Starting MCP server...');
  const serverProcess = spawn('node', [path.join(__dirname, 'dist/index.js')], {
    cwd: WORKSPACE,
    env: { ...process.env, LOG_LEVEL: 'info' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  // Log stderr
  let stderrBuf = '';
  serverProcess.stderr.on('data', (d) => { stderrBuf += d.toString(); });

  const transport = new StdioClientTransport({
    command: 'node',
    args: [path.join(__dirname, 'dist', 'index.js')],
    env: { ...process.env, LOG_LEVEL: 'info' },
    cwd: WORKSPACE,
  });

  const client = new Client({ name: 'test-client', version: '0.1.0' });

  try {
    await client.connect(transport);
    console.log('MCP client connected\n');

    // --- Test 1: List tools ---
    console.log('Test 1: List tools');
    const tools = await client.listTools();
    const toolNames = tools.tools.map(t => t.name);
    check('has definition tool', toolNames.includes('definition'));
    check('has hover tool', toolNames.includes('hover'));
    check('has workspace_symbols tool', toolNames.includes('workspace_symbols'));
    check('has diagnostics tool', toolNames.includes('diagnostics'));
    check('has references tool', toolNames.includes('references'));
    check('has rename_symbol tool', toolNames.includes('rename_symbol'));
    check('has code_actions tool', toolNames.includes('code_actions'));
    check('has document_symbols tool', toolNames.includes('document_symbols'));
    check('has implementations tool', toolNames.includes('implementations'));
    // Phase 2 tools (ENABLE_ALL_TOOLS=1)
    check('has supertypes tool', toolNames.includes('supertypes'));
    check('has subtypes tool', toolNames.includes('subtypes'));
    check('has incoming_calls tool', toolNames.includes('incoming_calls'));
    check('has outgoing_calls tool', toolNames.includes('outgoing_calls'));
    check('has format_file tool', toolNames.includes('format_file'));
    check('has organize_imports tool', toolNames.includes('organize_imports'));
    check('has completion tool', toolNames.includes('completion'));
    check('has type_definition tool', toolNames.includes('type_definition'));
    check('has inlay_hints tool', toolNames.includes('inlay_hints'));
    check('has code_lens tool', toolNames.includes('code_lens'));
    console.log(`  Total tools: ${toolNames.length}\n`);

    // --- Test 2: workspace_symbols ---
    console.log('Test 2: workspace_symbols');
    let result = await client.callTool({ name: 'workspace_symbols', arguments: { query: 'HoverProvider' } });
    let text = result.content[0]?.text || '';
    check('finds HoverProvider', text.includes('HoverProvider'));
    check('shows file location', text.includes('.scala'));

    // --- Test 3: definition ---
    console.log('Test 3: definition');
    result = await client.callTool({ name: 'definition', arguments: { symbolName: 'HoverProvider' } });
    text = result.content[0]?.text || '';
    check('returns source code', text.includes('class') || text.includes('HoverProvider'));
    check('includes line numbers', /\d+\|/.test(text));
    check('shows file path', text.includes('HoverProvider.scala'));

    // --- Test 4: hover ---
    console.log('Test 4: hover');
    result = await client.callTool({ name: 'hover', arguments: { filePath: TEST_FILE, line: 1, column: 10 } });
    text = result.content[0]?.text || '';
    check('returns hover info', text.length > 0, text.substring(0, 100));

    // --- Test 5: document_symbols ---
    console.log('Test 5: document_symbols');
    result = await client.callTool({ name: 'document_symbols', arguments: { filePath: TEST_FILE } });
    text = result.content[0]?.text || '';
    check('lists symbols', text.includes('class') || text.includes('method') || text.includes('object'));
    check('shows line numbers', /L\d+/.test(text));

    // --- Test 6: references ---
    console.log('Test 6: references');
    result = await client.callTool({ name: 'references', arguments: { symbolName: 'DefinitionProvider' } });
    text = result.content[0]?.text || '';
    check('finds references', text.includes('References') || text.includes('DefinitionProvider'));

    // --- Test 7: implementations ---
    console.log('Test 7: implementations');
    result = await client.callTool({ name: 'implementations', arguments: { symbolName: 'ScalaLspServer' } });
    text = result.content[0]?.text || '';
    check('returns result', text.length > 0, text.substring(0, 100));

    // --- Test 8: diagnostics ---
    console.log('Test 8: diagnostics');
    result = await client.callTool({ name: 'diagnostics', arguments: { filePath: TEST_FILE } });
    text = result.content[0]?.text || '';
    check('returns diagnostics result', text.includes('diagnostic') || text.includes('No diagnostics'));

    // --- Test 9: code_actions ---
    console.log('Test 9: code_actions');
    result = await client.callTool({ name: 'code_actions', arguments: {
      filePath: TEST_FILE, startLine: 1, startColumn: 1, endLine: 1, endColumn: 1
    }});
    text = result.content[0]?.text || '';
    check('returns code actions result', text.includes('code action') || text.includes('No code actions'));

    // --- Test 10: Phase 2 - incoming_calls ---
    console.log('Test 10: incoming_calls');
    result = await client.callTool({ name: 'incoming_calls', arguments: { symbolName: 'HoverProvider' } });
    text = result.content[0]?.text || '';
    check('returns callers result', text.length > 0, text.substring(0, 100));

    // --- Summary ---
    console.log(`\n=== Results: ${pass} passed, ${fail} failed ===`);

  } catch (err) {
    console.error('Test error:', err);
    console.error('\nServer stderr:\n', stderrBuf.split('\n').slice(-20).join('\n'));
    fail++;
  } finally {
    try { await client.close(); } catch {}
    // Kill the transport's child process
    transport.close?.();
    process.exit(fail > 0 ? 1 : 0);
  }
}

main();
