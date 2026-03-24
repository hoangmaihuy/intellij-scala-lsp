const { LanguageClient, TransportKind } = require("vscode-languageclient/node");
const vscode = require("vscode");

let client;
let outputChannel;
let statusBarItem;

function log(message) {
  const ts = new Date().toISOString().replace("T", " ").replace("Z", "");
  outputChannel.appendLine(`[${ts}] ${message}`);
}

function setStatus(icon, text, tooltip) {
  statusBarItem.text = `$(${icon}) ${text}`;
  statusBarItem.tooltip = tooltip || text;
  statusBarItem.show();
}

function shortUri(uri) {
  if (!uri) return "";
  const match = uri.match(/([^/]+)$/);
  return match ? match[1] : uri;
}

const MAX_PAYLOAD_BYTES = 10 * 1024; // 10KB

function truncatePayload(obj) {
  try {
    const json = JSON.stringify(obj, null, 2);
    if (json.length <= MAX_PAYLOAD_BYTES) return json;
    return json.substring(0, MAX_PAYLOAD_BYTES) + `\n... [truncated, ${(json.length / 1024).toFixed(0)}KB total]`;
  } catch {
    return "[unserializable]";
  }
}

// Middleware that logs requests with timing.
// vscode-languageclient middleware signature: (params, token, next) => result
// where `next` is the default handler provided by the framework.
function summarizeResult(result) {
  try {
    if (result == null) return "null";
    if (Array.isArray(result)) {
      const count = result.length;
      if (count === 0) return "[]";
      const first = result[0];
      if (first?.uri) {
        const uri = typeof first.uri === "string" ? shortUri(first.uri) : String(first.uri);
        return `${count} locations [${uri}:${first.range?.start?.line ?? "?"}]`;
      }
      if (first?.label) return `${count} items`;
      return `${count} results`;
    }
    if (result?.contents) return "hover";
    if (result?.signatures) return `${result.signatures.length} signatures`;
    if (result?.changes || result?.documentChanges) return "workspace edit";
    return typeof result;
  } catch {
    return "?";
  }
}

function createLoggingMiddleware(getVerbose) {
  function wrap(method) {
    return async function (...args) {
      // Last arg is always `next`, params is first, token may be in between
      const next = args[args.length - 1];
      const params = args[0];
      const file = params?.textDocument?.uri ? ` [${shortUri(params.textDocument.uri)}]` : "";
      const pos = params?.position ? `:${params.position.line}:${params.position.character}` : "";
      const start = Date.now();
      try {
        const result = await next(...args.slice(0, -1));
        const ms = Date.now() - start;
        let summary;
        try { summary = summarizeResult(result); } catch { summary = "?"; }
        log(`${method}${file}${pos} -> ${ms}ms ${summary}`);
        if (getVerbose()) {
          log(`  params: ${truncatePayload(params)}`);
          log(`  result: ${truncatePayload(result)}`);
        }
        return result;
      } catch (err) {
        const ms = Date.now() - start;
        log(`${method}${file}${pos} -> ERROR ${ms}ms: ${err?.message || err}`);
        if (getVerbose()) {
          log(`  params: ${truncatePayload(params)}`);
        }
        throw err;
      }
    };
  }

  return {
    provideCompletionItem: wrap("textDocument/completion"),
    provideHover: wrap("textDocument/hover"),
    provideDefinition: wrap("textDocument/definition"),
    provideTypeDefinition: wrap("textDocument/typeDefinition"),
    provideImplementation: wrap("textDocument/implementation"),
    provideReferences: wrap("textDocument/references"),
    provideDocumentSymbols: wrap("textDocument/documentSymbol"),
    provideSignatureHelp: wrap("textDocument/signatureHelp"),
    provideDocumentFormattingEdits: wrap("textDocument/formatting"),
    provideRenameEdits: wrap("textDocument/rename"),
    provideCodeActions: wrap("textDocument/codeAction"),
    didOpen: (data, next) => {
      log(`textDocument/didOpen [${shortUri(data.textDocument.uri)}]`);
      return next(data);
    },
    didClose: (data, next) => {
      log(`textDocument/didClose [${shortUri(data.textDocument.uri)}]`);
      return next(data);
    },
    didChange: (data, next) => {
      log(`textDocument/didChange [${shortUri(data.textDocument.uri)}]`);
      return next(data);
    },
    didSave: (data, next) => {
      log(`textDocument/didSave [${shortUri(data.textDocument.uri)}]`);
      return next(data);
    },
    handleDiagnostics: (uri, diagnostics, next) => {
      log(`textDocument/publishDiagnostics [${shortUri(uri.toString())}] ${diagnostics.length} diagnostic(s)`);
      return next(uri, diagnostics);
    },
  };
}

function activate(context) {
  outputChannel = vscode.window.createOutputChannel("IntelliJ Scala LSP");
  context.subscriptions.push(outputChannel);

  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 0);
  statusBarItem.command = "intellijScalaLsp.showOutput";
  context.subscriptions.push(statusBarItem);

  context.subscriptions.push(
    vscode.commands.registerCommand("intellijScalaLsp.showOutput", () => {
      outputChannel.show();
    })
  );

  log("Activating IntelliJ Scala LSP extension");
  setStatus("sync~spin", "Scala LSP: Starting", "IntelliJ Scala LSP — starting server");

  const config = vscode.workspace.getConfiguration("intellijScalaLsp");

  // Use configured launcher, or fall back to 'intellij-scala-lsp' in PATH
  const launcher = config.get("launcher") || "intellij-scala-lsp";

  let verboseLogging = config.get("verboseLogging", false);
  log(`Verbose logging: ${verboseLogging}`);
  log(`Launcher: ${launcher}`);

  const serverOptions = {
    command: launcher,
    args: [],
    transport: TransportKind.stdio,
  };

  const clientOptions = {
    documentSelector: [
      { scheme: "file", language: "scala" },
      { scheme: "file", pattern: "**/*.scala" },
      { scheme: "file", pattern: "**/*.sc" },
    ],
    outputChannel: outputChannel,
    middleware: createLoggingMiddleware(() => verboseLogging),
  };

  client = new LanguageClient(
    "intellijScalaLsp",
    "IntelliJ Scala LSP",
    serverOptions,
    clientOptions
  );

  client.onDidChangeState(({ oldState, newState }) => {
    const states = { 1: "Stopped", 2: "Starting", 3: "Running" };
    const newName = states[newState] || `Unknown(${newState})`;
    log(`Client state: ${states[oldState] || oldState} -> ${newName}`);
    if (newState === 3) {
      setStatus("sync~spin", "Scala LSP: Indexing", "IntelliJ Scala LSP — indexing project");
    } else if (newState === 1) {
      setStatus("circle-slash", "Scala LSP: Stopped", "IntelliJ Scala LSP — stopped");
    } else {
      setStatus("sync~spin", "Scala LSP: Starting", "IntelliJ Scala LSP — starting");
    }
  });

  log("Starting language client");
  client.start().then(
    () => {
      log("Language client started successfully");

      // Listen for window/logMessage to track indexing state
      client.onNotification("window/logMessage", (params) => {
        const msg = params.message || "";
        log(`[server] ${msg}`);
        if (msg.includes("Indexing project")) {
          setStatus("sync~spin", "Scala LSP: Indexing", "IntelliJ Scala LSP — indexing project");
        } else if (msg.includes("Indexing complete")) {
          setStatus("check", "Scala LSP", "IntelliJ Scala LSP — ready");
        }
      });
    },
    (err) => {
      log(`ERROR: Failed to start language client: ${err.message}`);
      setStatus("error", "Scala LSP: Error", `Failed to start: ${err.message}`);
      vscode.window.showErrorMessage(`IntelliJ Scala LSP failed to start: ${err.message}`);
    }
  );

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("intellijScalaLsp.verboseLogging")) {
        verboseLogging = vscode.workspace.getConfiguration("intellijScalaLsp").get("verboseLogging", false);
        log(`Verbose logging ${verboseLogging ? "enabled" : "disabled"}`);
      }
    })
  );

  context.subscriptions.push(client);
}

function deactivate() {
  if (statusBarItem) statusBarItem.dispose();
  if (client) {
    return client.stop();
  }
}

module.exports = { activate, deactivate };
