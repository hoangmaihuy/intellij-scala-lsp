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

function setStatus(text, tooltip) {
  statusBarItem.text = `$(symbol-class) ${text}`;
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
  setStatus("Scala LSP: Starting", "IntelliJ Scala LSP — starting server");

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
    setStatus("Scala LSP: Error", msg);
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
      setStatus("Scala LSP", "IntelliJ Scala LSP — running");
    } else if (newState === 1) {
      setStatus("Scala LSP: Stopped", "IntelliJ Scala LSP — stopped");
    } else {
      setStatus("Scala LSP: Starting", "IntelliJ Scala LSP — starting");
    }
  });

  log("Starting language client");
  client.start().then(
    () => log("Language client started successfully"),
    (err) => {
      log(`ERROR: Failed to start language client: ${err.message}`);
      setStatus("Scala LSP: Error", `Failed to start: ${err.message}`);
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
