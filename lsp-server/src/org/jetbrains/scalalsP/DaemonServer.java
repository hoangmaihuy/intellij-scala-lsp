package org.jetbrains.scalalsP;

import org.jetbrains.scalalsP.intellij.IntellijProjectManager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;

public final class DaemonServer {
    private final ProjectRegistry registry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private ServerSocket serverSocket;

    public DaemonServer(ProjectRegistry registry) {
        this.registry = registry;
    }

    /** Bind the server socket. Returns the actual bound port. Does NOT block. */
    public int bind(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        int boundPort = serverSocket.getLocalPort();
        System.err.println("[DaemonServer] Bound on port " + boundPort);
        return boundPort;
    }

    /** Accept connections in a loop. Blocks until stop() is called. Must call bind() first. */
    public void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.err.println("[DaemonServer] New connection from " +
                    clientSocket.getRemoteSocketAddress());
                Thread sessionThread = new Thread(
                    () -> handleConnection(clientSocket),
                    "lsp-session-" + activeSessions.incrementAndGet()
                );
                sessionThread.setDaemon(true);
                sessionThread.start();
            } catch (SocketException e) {
                if (running.get()) {
                    System.err.println("[DaemonServer] Accept error: " + e.getMessage());
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[DaemonServer] Accept error: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            // ignore
        }
        registry.closeAll();
        System.err.println("[DaemonServer] Stopped");
    }

    private void handleConnection(Socket socket) {
        String projectPath = null;
        try {
            InputStream rawIn = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] firstMessage = readFirstMessage(rawIn);

            // Check if this is a shutdown request (from --stop mode)
            String method = extractMethod(firstMessage);
            if ("shutdown".equals(method)) {
                System.err.println("[DaemonServer] Received shutdown signal");
                socket.close();
                stop();
                return;
            }

            // Check if this is an import request (from --import mode)
            if ("import".equals(method)) {
                handleImport(firstMessage, socket);
                return;
            }

            projectPath = extractProjectPath(firstMessage);
            System.err.println("[DaemonServer] Session for project: " + projectPath);

            // Replay consumed bytes + remaining stream for lsp4j
            InputStream replayedIn = new SequenceInputStream(
                new ByteArrayInputStream(firstMessage),
                rawIn
            );

            // Open or get cached project (with reference counting)
            var project = registry.acquireProject(projectPath);

            // Create per-session project manager sharing the Project
            var projectManager = new IntellijProjectManager(scala.Option.apply(registry), true);
            projectManager.setProjectForSession(project);

            // Create per-session LSP server in daemon mode
            var server = new ScalaLspServer(projectPath, projectManager, true);

            // Wire to lsp4j using same pattern as LspLauncher
            LspLauncher.startAndAwait(server, replayedIn, out);

        } catch (Exception e) {
            if ("health-check".equals(e.getMessage())) {
                // Silent: probe connection from MCP ensureDaemonRunning()
            } else {
                System.err.println("[DaemonServer] Session error: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } finally {
            if (projectPath != null) {
                registry.releaseProject(projectPath);
            }
            activeSessions.decrementAndGet();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleImport(byte[] messageBytes, Socket socket) {
        String projectPath = extractImportPath(messageBytes);
        System.err.println("[DaemonServer] Import request for: " + projectPath);
        try {
            OutputStream out = socket.getOutputStream();
            try {
                // Reuse existing project from registry, or open a new one (with reference counting)
                var project = registry.acquireProject(projectPath);
                try {
                    ProjectImporter.importSbtProjectWithExisting(project, Path.of(projectPath));
                    String result = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"success\":true}}";
                    writeJsonRpcMessage(out, result);
                    System.err.println("[DaemonServer] Import complete for: " + projectPath);
                } finally {
                    registry.releaseProject(projectPath);
                }
            } catch (Exception e) {
                System.err.println("[DaemonServer] Import failed: " + e.getMessage());
                e.printStackTrace(System.err);
                String error = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":" +
                    JsonParser.parseString("\"" + e.getMessage().replace("\\", "\\\\").replace("\"", "\\\"") + "\"") + "}}";
                writeJsonRpcMessage(out, error);
            }
        } catch (IOException e) {
            System.err.println("[DaemonServer] IO error during import: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void writeJsonRpcMessage(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private String extractImportPath(byte[] messageBytes) {
        String raw = new String(messageBytes, StandardCharsets.UTF_8);
        int jsonStart = raw.indexOf("{");
        if (jsonStart < 0) return "";
        String json = raw.substring(jsonStart);
        JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : null;
        if (params == null) return "";
        return params.has("projectPath") ? params.get("projectPath").getAsString() : "";
    }

    private byte[] readFirstMessage(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int contentLength = -1;
        StringBuilder headerBuf = new StringBuilder();
        int prev = -1, curr;
        while ((curr = in.read()) != -1) {
            buf.write(curr);
            headerBuf.append((char) curr);
            if (curr == '\n' && prev == '\r') {
                String line = headerBuf.toString().trim();
                if (line.isEmpty()) break;
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
                headerBuf.setLength(0);
            }
            prev = curr;
        }
        if (contentLength <= 0) {
            // Health check probe (e.g., MCP ensureDaemonRunning) — no LSP message
            throw new IOException("health-check");
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n == -1) throw new IOException("Unexpected EOF reading first message body");
            read += n;
        }
        buf.write(body);
        return buf.toByteArray();
    }

    private String extractProjectPath(byte[] messageBytes) {
        String raw = new String(messageBytes, StandardCharsets.UTF_8);
        int jsonStart = raw.indexOf("{");
        if (jsonStart < 0) return "";
        String json = raw.substring(jsonStart);

        JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : null;
        if (params == null) return "";

        String rootUri = params.has("rootUri") ? params.get("rootUri").getAsString() : null;
        if (rootUri == null) {
            rootUri = params.has("rootPath") ? params.get("rootPath").getAsString() : "";
        }
        if (rootUri.startsWith("file://")) {
            try {
                return new URI(rootUri).getPath();
            } catch (Exception e) {
                return rootUri.substring(7);
            }
        }
        return rootUri;
    }

    private String extractMethod(byte[] messageBytes) {
        String raw = new String(messageBytes, StandardCharsets.UTF_8);
        int jsonStart = raw.indexOf("{");
        if (jsonStart < 0) return "";
        String json = raw.substring(jsonStart);
        JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
        return msg.has("method") ? msg.get("method").getAsString() : "";
    }
}
