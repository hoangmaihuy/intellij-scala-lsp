const { LanguageClient, TransportKind } = require("vscode-languageclient/node");
const vscode = require("vscode");
const path = require("path");

let client;

function activate(context) {
  const config = vscode.workspace.getConfiguration("intellijScalaLsp");
  const launcher = config.get("launcher") || "/Users/hoangmei/Work/intellij-scala-lsp/launcher/launch-lsp.sh";
  const intellijHome = config.get("intellijHome") || "/Users/hoangmei/.intellij-scala-lspPluginIC/sdk/253.32098.37";

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
