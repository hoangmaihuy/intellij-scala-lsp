const { LanguageClient, TransportKind } = require("vscode-languageclient/node");
const vscode = require("vscode");
const path = require("path");
const fs = require("fs");

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

function findProjectRoot(extPath) {
  let dir = fs.realpathSync(extPath);
  for (let i = 0; i < 5; i++) {
    const candidate = path.join(dir, "launcher", "launch-lsp.sh");
    if (fs.existsSync(candidate)) return dir;
    dir = path.dirname(dir);
  }
  return null;
}

function findIntellijSdk() {
  const cacheDir = path.join(
    process.env.HOME || process.env.USERPROFILE || "",
    ".intellij-scala-lspPluginIC",
    "sdk"
  );
  if (fs.existsSync(cacheDir)) {
    const versions = fs.readdirSync(cacheDir).filter(f =>
      fs.statSync(path.join(cacheDir, f)).isDirectory()
    ).sort().reverse();
    if (versions.length > 0) {
      return path.join(cacheDir, versions[0]);
    }
  }
  return null;
}

function shortUri(uri) {
  if (!uri) return "";
  const match = uri.match(/([^/]+)$/);
  return match ? match[1] : uri;
}

// Middleware that logs requests with timing
function createLoggingMiddleware() {
  function wrapRequest(method, next) {
    return async function (params, token) {
      const file = params?.textDocument?.uri ? ` [${shortUri(params.textDocument.uri)}]` : "";
      const start = Date.now();
      try {
        const result = await next(params, token);
        const ms = Date.now() - start;
        log(`${method}${file} -> ${ms}ms`);
        return result;
      } catch (err) {
        const ms = Date.now() - start;
        log(`${method}${file} -> ERROR ${ms}ms: ${err.message}`);
        throw err;
      }
    };
  }

  return {
    provideCompletionItem: wrapRequest("textDocument/completion", (p, t) => client.sendRequest("textDocument/completion", p, t)),
    provideHover: wrapRequest("textDocument/hover", (p, t) => client.sendRequest("textDocument/hover", p, t)),
    provideDefinition: wrapRequest("textDocument/definition", (p, t) => client.sendRequest("textDocument/definition", p, t)),
    provideTypeDefinition: wrapRequest("textDocument/typeDefinition", (p, t) => client.sendRequest("textDocument/typeDefinition", p, t)),
    provideImplementation: wrapRequest("textDocument/implementation", (p, t) => client.sendRequest("textDocument/implementation", p, t)),
    provideReferences: wrapRequest("textDocument/references", (p, t) => client.sendRequest("textDocument/references", p, t)),
    provideDocumentSymbols: wrapRequest("textDocument/documentSymbol", (p, t) => client.sendRequest("textDocument/documentSymbol", p, t)),
    provideSignatureHelp: wrapRequest("textDocument/signatureHelp", (p, t) => client.sendRequest("textDocument/signatureHelp", p, t)),
    provideDocumentFormattingEdits: wrapRequest("textDocument/formatting", (p, t) => client.sendRequest("textDocument/formatting", p, t)),
    provideRenameEdits: wrapRequest("textDocument/rename", (p, t) => client.sendRequest("textDocument/rename", p, t)),
    provideCodeActions: wrapRequest("textDocument/codeAction", (p, t) => client.sendRequest("textDocument/codeAction", p, t)),
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
  const extPath = context.extensionPath;
  const projectRoot = findProjectRoot(extPath);

  const launcher = config.get("launcher") ||
    (projectRoot ? path.join(projectRoot, "launcher", "launch-lsp.sh") : "");
  const intellijHome = config.get("intellijHome") || findIntellijSdk() || "";

  log(`Extension path: ${extPath}`);
  log(`Project root: ${projectRoot || "(not found)"}`);
  log(`Launcher: ${launcher || "(not set)"}`);
  log(`IntelliJ SDK: ${intellijHome || "(not set)"}`);

  if (!launcher || !fs.existsSync(launcher)) {
    const msg = `Launcher not found: ${launcher || "(empty)"}. Set intellijScalaLsp.launcher in settings.`;
    log(`ERROR: ${msg}`);
    setStatus("error", "Scala LSP: Error", msg);
    vscode.window.showErrorMessage(`IntelliJ Scala LSP: ${msg}`);
    return;
  }

  if (!intellijHome) {
    log("WARNING: IntelliJ SDK not detected. Server may fail to start.");
  }

  const serverOptions = {
    command: "bash",
    args: [launcher],
    options: {
      env: {
        ...process.env,
        INTELLIJ_HOME: intellijHome,
      },
    },
    transport: TransportKind.stdio,
  };

  const clientOptions = {
    documentSelector: [
      { scheme: "file", language: "scala" },
      { scheme: "file", pattern: "**/*.scala" },
      { scheme: "file", pattern: "**/*.sc" },
    ],
    outputChannel: outputChannel,
    middleware: createLoggingMiddleware(),
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

  context.subscriptions.push(client);
}

function deactivate() {
  if (statusBarItem) statusBarItem.dispose();
  if (client) {
    return client.stop();
  }
}

module.exports = { activate, deactivate };
