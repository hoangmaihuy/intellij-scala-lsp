const { LanguageClient, TransportKind } = require("vscode-languageclient/node");
const vscode = require("vscode");
const path = require("path");
const fs = require("fs");

let client;

function findProjectRoot(extPath) {
  // Extension lives at <project>/vscode-extension or is symlinked there
  // Walk up to find launcher/launch-lsp.sh
  let dir = fs.realpathSync(extPath);
  for (let i = 0; i < 5; i++) {
    const candidate = path.join(dir, "launcher", "launch-lsp.sh");
    if (fs.existsSync(candidate)) return dir;
    dir = path.dirname(dir);
  }
  return null;
}

function findIntellijSdk(projectRoot) {
  // Check common locations for the sbt-downloaded SDK
  const cacheDir = path.join(
    process.env.HOME || process.env.USERPROFILE || "",
    ".intellij-scala-lspPluginIC",
    "sdk"
  );
  if (fs.existsSync(cacheDir)) {
    // Pick the latest SDK version directory
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
  const config = vscode.workspace.getConfiguration("intellijScalaLsp");
  const extPath = context.extensionPath;
  const projectRoot = findProjectRoot(extPath);

  const launcher = config.get("launcher") ||
    (projectRoot ? path.join(projectRoot, "launcher", "launch-lsp.sh") : "");
  const intellijHome = config.get("intellijHome") ||
    (projectRoot ? findIntellijSdk(projectRoot) : "") || "";

  if (!launcher || !fs.existsSync(launcher)) {
    vscode.window.showErrorMessage(
      "IntelliJ Scala LSP: launcher not found. Set intellijScalaLsp.launcher in settings."
    );
    return;
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
    outputChannelName: "IntelliJ Scala LSP",
  };

  client = new LanguageClient(
    "intellijScalaLsp",
    "IntelliJ Scala LSP",
    serverOptions,
    clientOptions
  );

  client.start();
  context.subscriptions.push(client);

  vscode.window.showInformationMessage("IntelliJ Scala LSP starting...");
}

function deactivate() {
  if (client) {
    return client.stop();
  }
}

module.exports = { activate, deactivate };
