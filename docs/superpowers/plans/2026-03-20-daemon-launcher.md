# Daemon Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the LSP server from per-session stdio to a persistent TCP daemon that bootstraps IntelliJ once and serves multiple projects/sessions.

**Architecture:** One long-lived JVM process bootstraps IntelliJ via `TestApplicationManager`, manages projects in a shared `ProjectRegistry`, and accepts TCP connections via `DaemonServer`. Each connection becomes an independent LSP session routed to the correct project by intercepting the `initialize` request's `rootUri`. The launcher script auto-starts the daemon if needed and proxies stdio↔TCP via `socat`.

**Tech Stack:** Scala 3.8, Java 21, lsp4j 0.23.1, IntelliJ Platform SDK 253, JUnit 4, socat

**Spec:** `docs/superpowers/specs/2026-03-20-daemon-launcher-design.md`

---

### Task 1: BootstrapState — extract shared latch

Extract `bootstrapComplete` from `ScalaLspMain` into a shared object so both stdio mode and daemon mode can reference it. Tests also reference this latch (`ScalaLspTestBase.setUp()` calls `ScalaLspMain.bootstrapComplete.countDown()`).

**Files:**
- Create: `lsp-server/src/org/jetbrains/scalalsP/BootstrapState.java`
- Modify: `lsp-server/src/ScalaLspMain.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala:39` (references `ScalaLspMain.bootstrapComplete`)
- Modify: `lsp-server/test/src/integration/ScalaLspTestBase.scala:21`
- Modify: `lsp-server/test/src/ScalaLspServerTest.scala:11`

- [ ] **Step 1: Create `BootstrapState.java`**

```java
package org.jetbrains.scalalsP;

import java.util.concurrent.CountDownLatch;

/** Shared bootstrap state accessible from ScalaLspMain (stdio), DaemonServer, and tests. */
public final class BootstrapState {
    public static final CountDownLatch bootstrapComplete = new CountDownLatch(1);
    private BootstrapState() {}
}
```

Java class because sbt-idea-plugin reliably packages Java classes that are referenced from Scala code.

- [ ] **Step 2: Update `ScalaLspMain.scala`**

Replace `val bootstrapComplete = new CountDownLatch(1)` with delegation:

```scala
object ScalaLspMain:
  /** @deprecated Use BootstrapState.bootstrapComplete directly */
  val bootstrapComplete = BootstrapState.bootstrapComplete
```

Keep the alias for now so nothing breaks yet. The latch countdown in `finally` block stays: `bootstrapComplete.countDown()`.

- [ ] **Step 3: Update `ScalaLspServer.scala:39`**

Change:
```scala
if !ScalaLspMain.bootstrapComplete.await(5, java.util.concurrent.TimeUnit.MINUTES) then
```
To:
```scala
if !BootstrapState.bootstrapComplete.await(5, java.util.concurrent.TimeUnit.MINUTES) then
```

- [ ] **Step 4: Update test references**

In `ScalaLspTestBase.scala:21`:
```scala
BootstrapState.bootstrapComplete.countDown()
```

In `ScalaLspServerTest.scala:11`:
```scala
BootstrapState.bootstrapComplete.countDown()
```

- [ ] **Step 5: Run tests**

Run: `sbt 'lsp-server/test'`
Expected: All 233 tests pass

- [ ] **Step 6: Commit**

```bash
git add lsp-server/src/org/jetbrains/scalalsP/BootstrapState.java \
  lsp-server/src/ScalaLspMain.scala lsp-server/src/ScalaLspServer.scala \
  lsp-server/test/src/integration/ScalaLspTestBase.scala \
  lsp-server/test/src/ScalaLspServerTest.scala
git commit -m "refactor: extract BootstrapState from ScalaLspMain"
```

---

### Task 2: ProjectRegistry — shared project instance management

Create the thread-safe registry that manages `Project` instances. This replaces the project open/close logic in `IntellijProjectManager` for daemon mode, and will also be used by stdio mode.

**Files:**
- Create: `lsp-server/src/org/jetbrains/scalalsP/ProjectRegistry.scala`
- Create: `lsp-server/test/src/ProjectRegistryTest.scala`

- [ ] **Step 1: Write test**

```scala
package org.jetbrains.scalalsP

import org.junit.Test
import org.junit.Assert.*

class ProjectRegistryTest:
  @Test def testGetProjectReturnsNoneForUnknown(): Unit =
    val registry = ProjectRegistry()
    assertEquals(None, registry.getProject("/nonexistent"))

  @Test def testOpenProjectReturnsSameInstanceOnSecondCall(): Unit =
    // This test verifies the caching contract — actual project open
    // requires IntelliJ bootstrap so we test the registry logic only
    val registry = ProjectRegistry()
    // getProject returns None for unopened project
    assertEquals(None, registry.getProject("/some/path"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'lsp-server/testOnly org.jetbrains.scalalsP.ProjectRegistryTest'`
Expected: FAIL — class not found

- [ ] **Step 3: Implement `ProjectRegistry`**

```scala
package org.jetbrains.scalalsP

import com.intellij.openapi.application.{ApplicationManager, WriteAction}
import com.intellij.openapi.project.{DumbService, Project, ProjectManager}
import com.intellij.openapi.projectRoots.ProjectJdkTable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Thread-safe registry of open IntelliJ projects shared across LSP sessions. */
class ProjectRegistry:
  private val projects = new ConcurrentHashMap[String, Project]()

  /** Get an already-open project, or None. */
  def getProject(path: String): Option[Project] =
    Option(projects.get(canonicalize(path)))

  /** Open a project (or return cached). Blocks until smart mode. Thread-safe. */
  def openProject(path: String): Project =
    val canonical = canonicalize(path)
    projects.computeIfAbsent(canonical, _ => doOpenProject(canonical))

  /** Close all projects. Called during daemon shutdown only. */
  def closeAll(): Unit =
    projects.values().asScala.foreach: project =>
      try
        ApplicationManager.getApplication.invokeAndWait: () =>
          ProjectManager.getInstance().closeAndDispose(project)
      catch case e: Exception =>
        System.err.println(s"[ProjectRegistry] Error closing ${project.getName}: ${e.getMessage}")
    projects.clear()

  private def doOpenProject(projectPath: String): Project =
    System.err.println(s"[ProjectRegistry] Opening project at: $projectPath")
    val project = ProjectManager.getInstance().loadAndOpenProject(projectPath)
    if project == null then
      throw RuntimeException(s"Failed to open project at $projectPath")
    System.err.println(s"[ProjectRegistry] Project opened: ${project.getName}")
    registerMissingJdks(project)
    // Wait for indexing to complete before returning
    System.err.println(s"[ProjectRegistry] Waiting for indexing: ${project.getName}")
    DumbService.getInstance(project).waitForSmartMode()
    System.err.println(s"[ProjectRegistry] Indexing complete: ${project.getName}")
    project

  private def registerMissingJdks(project: Project): Unit =
    try
      val jdkTable = ProjectJdkTable.getInstance()
      val javaSdkType = com.intellij.openapi.projectRoots.SdkType.EP_NAME.getExtensionList.asScala
        .find(_.getName == "JavaSDK").orNull
      if javaSdkType == null then return

      import com.intellij.openapi.module.ModuleManager
      val modules = ModuleManager.getInstance(project).getModules
      val referencedJdkNames = scala.collection.mutable.Set[String]()
      for m <- modules do
        val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
        if rootManager.getSdk == null then
          val model = rootManager.getModifiableModel
          try Option(model.getSdkName).foreach(n => referencedJdkNames += n)
          finally model.dispose()

      for jdkName <- referencedJdkNames do
        if jdkTable.findJdk(jdkName) == null then
          findJdkHome().foreach: home =>
            System.err.println(s"[ProjectRegistry] Registering JDK '$jdkName' -> $home")
            ApplicationManager.getApplication.invokeAndWait: () =>
              WriteAction.run[RuntimeException]: () =>
                val createJdk = javaSdkType.getClass.getMethod("createJdk",
                  classOf[String], classOf[String], classOf[Boolean])
                val sdk = createJdk.invoke(javaSdkType, jdkName, home, java.lang.Boolean.FALSE)
                  .asInstanceOf[com.intellij.openapi.projectRoots.Sdk]
                jdkTable.addJdk(sdk)
    catch case e: Exception =>
      System.err.println(s"[ProjectRegistry] JDK registration failed: ${e.getMessage}")

  private def findJdkHome(): Option[String] =
    val candidates = Seq(
      Option(System.getenv("JAVA_HOME")),
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr/Contents/Home"),
      Some(com.intellij.openapi.application.PathManager.getHomePath + "/jbr"),
      Some("/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"),
      Some("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"),
    ).flatten
    candidates.find(p => java.io.File(p + "/bin/java").exists())

  private def canonicalize(path: String): String =
    Path.of(path).toAbsolutePath.normalize.toString
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'lsp-server/testOnly org.jetbrains.scalalsP.ProjectRegistryTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lsp-server/src/org/jetbrains/scalalsP/ProjectRegistry.scala \
  lsp-server/test/src/ProjectRegistryTest.scala
git commit -m "feat: add ProjectRegistry for shared project instance management"
```

---

### Task 3: Update IntellijProjectManager to use ProjectRegistry

Make `IntellijProjectManager` delegate project open/close to `ProjectRegistry` and add `daemonMode` flag so `closeProject()` only clears per-session state in daemon mode.

**Files:**
- Modify: `lsp-server/src/intellij/IntellijProjectManager.scala`

- [ ] **Step 1: Refactor IntellijProjectManager**

Add a `ProjectRegistry` parameter and `daemonMode` flag. Keep the no-arg constructor for backward compatibility (stdio mode and tests).

Key changes to `IntellijProjectManager.scala`:
- Add constructor: `class IntellijProjectManager(registry: Option[ProjectRegistry] = None, daemonMode: Boolean = false)`
- `openProject()`: if `registry.isDefined`, delegate to `registry.get.openProject(path)` and assign `project = result`. Otherwise keep current behavior.
- `closeProject()`: if `daemonMode`, only clear `virtualFileCache` and set `project = null` (do NOT call `closeAndDispose`). Otherwise keep current behavior.
- Remove `registerMissingJdks()` and `findJdkHome()` — these are now in `ProjectRegistry`.
- Remove `waitForSmartMode()` from `openProject` — `ProjectRegistry.openProject` already waits.
- Keep `waitForSmartMode()` as a public method (used by `ScalaLspServer.initialized()`).

- [ ] **Step 2: Run all tests**

Run: `sbt 'lsp-server/test'`
Expected: All 233 tests pass (no-arg constructor preserves current behavior)

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/intellij/IntellijProjectManager.scala
git commit -m "refactor: IntellijProjectManager delegates to ProjectRegistry"
```

---

### Task 4: ScalaLspServer daemon mode — guard shutdown/exit

Add `daemonMode: Boolean` parameter so `shutdown()` doesn't close the shared project and `exit()` doesn't kill the JVM.

**Files:**
- Modify: `lsp-server/src/ScalaLspServer.scala`
- Test: `lsp-server/test/src/ScalaLspServerTest.scala`

- [ ] **Step 1: Add daemonMode parameter**

Change constructor to:
```scala
class ScalaLspServer(
  projectPath: String,
  projectManager: IntellijProjectManager,
  daemonMode: Boolean = false
) extends LanguageServer with LanguageClientAware:

  def this(projectPath: String) = this(projectPath, IntellijProjectManager(), false)
  def this(projectPath: String, pm: IntellijProjectManager) = this(projectPath, pm, false)
```

- [ ] **Step 2: Guard shutdown()**

```scala
override def shutdown(): CompletableFuture[AnyRef] =
  CompletableFuture.supplyAsync: () =>
    System.err.println("[ScalaLsp] Shutting down...")
    if !daemonMode then projectManager.closeProject()
    null
```

- [ ] **Step 3: Guard exit()**

```scala
override def exit(): Unit =
  System.err.println("[ScalaLsp] Exiting")
  if !daemonMode then System.exit(0)
```

- [ ] **Step 4: Run tests**

Run: `sbt 'lsp-server/test'`
Expected: All 233 tests pass (default `daemonMode=false`)

- [ ] **Step 5: Commit**

```bash
git add lsp-server/src/ScalaLspServer.scala
git commit -m "feat: add daemonMode flag to guard shutdown/exit"
```

---

### Task 5: DaemonServer — TCP server with initialize intercept

The core daemon component. Accepts TCP connections, intercepts the `initialize` message to extract `rootUri`, routes to the correct project, and creates per-session LSP servers.

**Files:**
- Create: `lsp-server/src/org/jetbrains/scalalsP/DaemonServer.java`

- [ ] **Step 1: Implement DaemonServer**

```java
package org.jetbrains.scalalsP;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.*;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.*;
import org.jetbrains.scalalsP.intellij.IntellijProjectManager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class DaemonServer {
    private final ProjectRegistry registry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private ServerSocket serverSocket;

    public DaemonServer(ProjectRegistry registry) {
        this.registry = registry;
    }

    /** Bind the server socket and return the actual bound port. Does NOT block. */
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
                // else: server was stopped, expected
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
        try {
            InputStream rawIn = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read the first JSON-RPC message to extract initialize params
            byte[] firstMessage = readFirstMessage(rawIn);

            // Check if this is a shutdown request (from --stop mode)
            String method = extractMethod(firstMessage);
            if ("shutdown".equals(method)) {
                System.err.println("[DaemonServer] Received shutdown signal");
                stop();
                return;
            }

            String projectPath = extractProjectPath(firstMessage);
            System.err.println("[DaemonServer] Session for project: " + projectPath);

            // Replay the consumed bytes + remaining stream for lsp4j
            InputStream replayedIn = new SequenceInputStream(
                new ByteArrayInputStream(firstMessage),
                rawIn
            );

            // Open or get cached project
            var project = registry.openProject(projectPath);

            // Create per-session project manager sharing the Project
            var projectManager = new IntellijProjectManager(scala.Option.apply(registry), true);
            projectManager.setProjectForSession(project);

            // Create per-session LSP server in daemon mode
            var server = new ScalaLspServer(projectPath, projectManager, true);

            // Wire to lsp4j using same pattern as LspLauncher
            startLspSession(server, replayedIn, out);

        } catch (Exception e) {
            System.err.println("[DaemonServer] Session error: " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            activeSessions.decrementAndGet();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Read bytes until a complete JSON-RPC message (Content-Length header + body).
     * Returns all consumed bytes so they can be replayed to lsp4j.
     */
    private byte[] readFirstMessage(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Read headers until \r\n\r\n
        int contentLength = -1;
        StringBuilder headerBuf = new StringBuilder();
        int prev = -1, curr;
        while ((curr = in.read()) != -1) {
            buf.write(curr);
            headerBuf.append((char) curr);
            if (curr == '\n' && prev == '\r') {
                String line = headerBuf.toString().trim();
                if (line.isEmpty()) break; // end of headers
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
                headerBuf.setLength(0);
            }
            prev = curr;
        }
        if (contentLength <= 0) {
            throw new IOException("No Content-Length header in first message");
        }
        // Read body
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
        // Find the JSON body after headers
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
                return new java.net.URI(rootUri).getPath();
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

    /**
     * Start an LSP session using the same lsp4j wiring as LspLauncher.
     * Reuses the Java wrapper pattern to avoid Scala 3 bridge method issues.
     */
    private void startLspSession(ScalaLspServer server, InputStream in, OutputStream out)
            throws Exception {
        // This method mirrors LspLauncher.startAndAwait but with arbitrary streams
        LspLauncher.startAndAwait(server, in, out);
    }
}
```

Note: `IntellijProjectManager` needs a new `setProjectForSession(Project)` method (added in Task 3) that sets the project without calling `openProject`. This is similar to `setProjectForTesting` but for daemon sessions.

- [ ] **Step 2: Add `setProjectForSession` to IntellijProjectManager**

Add to `IntellijProjectManager.scala`:
```scala
/** For daemon mode — set a project from ProjectRegistry without opening it */
private[scalalsP] def setProjectForSession(p: Project): Unit =
  project = p
```

- [ ] **Step 3: Verify it compiles**

Run: `sbt 'lsp-server/compile'`
Expected: Compiles without errors

- [ ] **Step 4: Commit**

```bash
git add lsp-server/src/org/jetbrains/scalalsP/DaemonServer.java \
  lsp-server/src/intellij/IntellijProjectManager.scala
git commit -m "feat: add DaemonServer with TCP accept and initialize intercept"
```

---

### Task 6: ScalaLspMain — mode dispatch

Add `--daemon` and `--stop` argument handling. The `--daemon` mode bootstraps IntelliJ and starts `DaemonServer`. The `--stop` mode reads the port file and sends a shutdown signal. Stdio mode is unchanged.

**Files:**
- Modify: `lsp-server/src/ScalaLspMain.scala`

- [ ] **Step 1: Rewrite ScalaLspMain with mode dispatch**

```scala
package org.jetbrains.scalalsP

import com.intellij.testFramework.LoggedErrorProcessor
import java.io.{PrintStream, ByteArrayOutputStream}
import java.util.EnumSet

object ScalaLspMain:
  // Keep alias for backward compatibility with tests
  val bootstrapComplete = BootstrapState.bootstrapComplete

  private val CACHE_DIR = s"${System.getProperty("user.home")}/.cache/intellij-scala-lsp"

  def main(args: Array[String]): Unit =
    val argList = args.toList
    argList match
      case "--daemon" :: projects =>
        daemonMode(projects)
      case "--stop" :: _ =>
        stopMode()
      case _ =>
        stdioMode(argList.headOption.getOrElse(""))

  private def daemonMode(projectPaths: List[String]): Unit =
    System.err.println("[ScalaLsp] Starting daemon mode...")
    val port = Option(System.getenv("LSP_PORT")).map(_.toInt).getOrElse(5007)

    // Redirect System.out to stderr (IntelliJ may print to stdout)
    System.setOut(new PrintStream(System.err, true))

    // Install global lenient error processor via reflection (field is private static volatile)
    val field = classOf[LoggedErrorProcessor].getDeclaredField("ourInstance")
    field.setAccessible(true)
    field.set(null, new LoggedErrorProcessor:
      override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
        EnumSet.of(LoggedErrorProcessor.Action.STDERR)
    )

    // Bootstrap IntelliJ
    try
      IntellijBootstrap.initialize()
      System.err.println("[ScalaLsp] IntelliJ platform initialized")
      BootstrapState.bootstrapComplete.countDown()
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Bootstrap failed: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

    // Pre-warm projects
    val registry = ProjectRegistry()
    for path <- projectPaths do
      try
        System.err.println(s"[ScalaLsp] Pre-warming project: $path")
        registry.openProject(path)
      catch
        case e: Exception =>
          System.err.println(s"[ScalaLsp] Failed to pre-warm $path: ${e.getMessage}")

    // Start TCP server
    val daemon = DaemonServer(registry)

    // Write state files AFTER binding port
    Runtime.getRuntime.addShutdownHook(new Thread(() =>
      new java.io.File(s"$CACHE_DIR/daemon.pid").delete()
      new java.io.File(s"$CACHE_DIR/daemon.port").delete()
    ))

    try
      // Bind first to get actual port, then write state files, then accept
      val boundPort = daemon.bind(port)
      java.nio.file.Files.createDirectories(java.nio.file.Path.of(CACHE_DIR))
      java.nio.file.Files.writeString(
        java.nio.file.Path.of(s"$CACHE_DIR/daemon.pid"),
        ProcessHandle.current().pid().toString
      )
      java.nio.file.Files.writeString(
        java.nio.file.Path.of(s"$CACHE_DIR/daemon.port"),
        boundPort.toString
      )
      daemon.acceptLoop() // blocks
    catch
      case e: java.net.BindException =>
        System.err.println(s"[ScalaLsp] Port $port already in use. Set LSP_PORT to use a different port.")
        System.exit(1)

  private def stopMode(): Unit =
    val portFile = java.nio.file.Path.of(s"$CACHE_DIR/daemon.port")
    val pidFile = java.nio.file.Path.of(s"$CACHE_DIR/daemon.pid")
    if !java.nio.file.Files.exists(portFile) then
      System.err.println("[ScalaLsp] No daemon running (no port file)")
      System.exit(1)

    val port = java.nio.file.Files.readString(portFile).trim.toInt
    val pid = if java.nio.file.Files.exists(pidFile) then
      java.nio.file.Files.readString(pidFile).trim.toLong
    else -1L

    // Send shutdown request over TCP
    try
      val socket = new java.net.Socket("localhost", port)
      val out = socket.getOutputStream
      val shutdownMsg = """{"jsonrpc":"2.0","id":1,"method":"shutdown","params":null}"""
      val header = s"Content-Length: ${shutdownMsg.length}\r\n\r\n"
      out.write(header.getBytes)
      out.write(shutdownMsg.getBytes)
      out.flush()
      socket.close()
      System.err.println(s"[ScalaLsp] Shutdown signal sent to daemon on port $port")
    catch
      case _: java.net.ConnectException =>
        System.err.println("[ScalaLsp] Daemon not responding, cleaning up stale files")

    // Clean up
    java.nio.file.Files.deleteIfExists(portFile)
    java.nio.file.Files.deleteIfExists(pidFile)

    // Kill the process if it's still alive
    if pid > 0 then
      ProcessHandle.of(pid).ifPresent(_.destroy())

  private def stdioMode(projectPath: String): Unit =
    System.err.println("[ScalaLsp] Starting IntelliJ Scala LSP server...")

    val jsonRpcOut = System.out
    System.setOut(new PrintStream(System.err, true))

    val bootstrapThread = new Thread(() =>
      try
        LoggedErrorProcessor.executeWith(new LoggedErrorProcessor:
          override def processError(category: String, message: String, details: Array[String], t: Throwable): java.util.Set[LoggedErrorProcessor.Action] =
            EnumSet.of(LoggedErrorProcessor.Action.STDERR)
        )
        IntellijBootstrap.initialize()
        System.err.println("[ScalaLsp] IntelliJ platform initialized")
      catch
        case e: Exception =>
          System.err.println(s"[ScalaLsp] Bootstrap failed: ${e.getMessage}")
          e.printStackTrace(System.err)
      finally
        BootstrapState.bootstrapComplete.countDown()
    , "intellij-bootstrap")
    bootstrapThread.setDaemon(true)
    bootstrapThread.start()

    try
      val server = new ScalaLspServer(projectPath)
      LspLauncher.startAndAwait(server, System.in, jsonRpcOut)
      System.err.println("[ScalaLsp] LSP connection closed")
      System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)
```

- [ ] **Step 2: Run tests**

Run: `sbt 'lsp-server/test'`
Expected: All 233 tests pass (stdio mode is the default path, tests don't use `--daemon`)

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/ScalaLspMain.scala
git commit -m "feat: add --daemon and --stop mode dispatch to ScalaLspMain"
```

---

### Task 7: Launcher script — daemon management and socat proxy

Add `--daemon`, `--stop` modes to `launch-lsp.sh` and stdio↔TCP proxy via socat.

**Files:**
- Modify: `launcher/launch-lsp.sh`

- [ ] **Step 1: Add daemon functions to launch-lsp.sh**

Add before the `# --- Launch ---` section:

```bash
# --- Daemon management ---

daemon_running() {
  local pidfile="$CACHE_DIR/daemon.pid"
  local portfile="$CACHE_DIR/daemon.port"
  [ -f "$pidfile" ] && [ -f "$portfile" ] || return 1
  local pid
  pid=$(cat "$pidfile")
  kill -0 "$pid" 2>/dev/null
}

wait_for_port_file() {
  local timeout=$1
  local portfile="$CACHE_DIR/daemon.port"
  local elapsed=0
  while [ ! -f "$portfile" ] && [ $elapsed -lt $timeout ]; do
    sleep 1
    elapsed=$((elapsed + 1))
  done
  [ -f "$portfile" ]
}

start_daemon_background() {
  echo "[launch-lsp] Starting daemon in background..." >&2
  "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" \
    org.jetbrains.scalalsP.ScalaLspMain --daemon "$@" \
    >> "$LSP_LOG" 2>&1 &
  echo "[launch-lsp] Daemon PID: $!" >&2
}

proxy_stdio_to_tcp() {
  local port=$1
  if ! command -v socat >/dev/null 2>&1; then
    echo "[launch-lsp] ERROR: socat is required. Install with: brew install socat" >&2
    exit 1
  fi
  exec socat STDIO TCP:localhost:$port
}
```

- [ ] **Step 2: Replace the launch section**

Replace `exec "$JAVA" ... org.jetbrains.scalalsP.ScalaLspMain "$@"` with:

```bash
# --- Launch ---

case "${1:-}" in
  --daemon)
    shift
    # Start daemon in foreground
    exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" \
      org.jetbrains.scalalsP.ScalaLspMain --daemon "$@"
    ;;
  --stop)
    exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" \
      org.jetbrains.scalalsP.ScalaLspMain --stop
    ;;
  *)
    # Connect mode: proxy to daemon, auto-start if needed
    if daemon_running; then
      DAEMON_PORT=$(cat "$CACHE_DIR/daemon.port")
      echo "[launch-lsp] Connecting to daemon on port $DAEMON_PORT" >&2
      proxy_stdio_to_tcp "$DAEMON_PORT"
    else
      # Auto-start daemon
      start_daemon_background "$@"
      if wait_for_port_file 60; then
        DAEMON_PORT=$(cat "$CACHE_DIR/daemon.port")
        echo "[launch-lsp] Daemon started, connecting on port $DAEMON_PORT" >&2
        proxy_stdio_to_tcp "$DAEMON_PORT"
      else
        echo "[launch-lsp] ERROR: Daemon failed to start within 60s. Check $LSP_LOG" >&2
        exit 1
      fi
    fi
    ;;
esac
```

- [ ] **Step 3: Test daemon mode manually**

```bash
# Terminal 1: Start daemon
bash launcher/launch-lsp.sh --daemon /Users/hoangmei/Work/osiris

# Terminal 2: Test connection
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":1,"rootUri":"file:///Users/hoangmei/Work/osiris","capabilities":{}}}' | socat - TCP:localhost:5007

# Terminal 3: Stop daemon
bash launcher/launch-lsp.sh --stop
```

- [ ] **Step 4: Commit**

```bash
git add launcher/launch-lsp.sh
git commit -m "feat: add daemon management and socat proxy to launcher"
```

---

### Task 8: Integration test — daemon mode end-to-end

Test the daemon with a real TCP connection using the same pattern as `LspEndToEndIntegrationTest`.

**Files:**
- Create: `lsp-server/test/src/integration/DaemonServerIntegrationTest.scala`

- [ ] **Step 1: Write integration test**

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.jetbrains.scalalsP.{BootstrapState, DaemonServer, JavaTestLanguageClient, ProjectRegistry}
import org.junit.Assert.*

import java.net.Socket
import java.util.concurrent.{CompletableFuture, TimeUnit}

/**
 * Tests DaemonServer TCP accept, initialize intercept, and session routing.
 */
class DaemonServerIntegrationTest extends ScalaLspTestBase:

  private var daemon: DaemonServer = scala.compiletime.uninitialized
  private var daemonThread: Thread = scala.compiletime.uninitialized
  private var registry: ProjectRegistry = scala.compiletime.uninitialized
  private val TEST_PORT = 15007 // avoid conflict with real daemon

  override def setUp(): Unit =
    super.setUp()
    // Note: super.setUp() already calls BootstrapState.bootstrapComplete.countDown()
    registry = ProjectRegistry()
    // Pre-register the test project in the registry
    registry.registerForTesting(getProject.getBasePath, getProject)
    daemon = DaemonServer(registry)
    daemon.bind(TEST_PORT)
    daemonThread = new Thread(() =>
      try daemon.acceptLoop()
      catch case _: Exception => ()
    , "daemon-test")
    daemonThread.setDaemon(true)
    daemonThread.start()

  override def tearDown(): Unit =
    daemon.stop()
    daemonThread.join(5000)
    super.tearDown()

  def testDaemonAcceptsConnectionAndInitializes(): Unit =
    val socket = new Socket("localhost", TEST_PORT)
    val launcher = new Launcher.Builder[LanguageServer]()
      .setLocalService(new JavaTestLanguageClient())
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .create()
    val proxy = launcher.getRemoteProxy
    launcher.startListening()

    val result = requestOffEdt() {
      val params = new InitializeParams()
      params.setProcessId(1)
      params.setRootUri(s"file://${getProject.getBasePath}")
      params.setCapabilities(new ClientCapabilities())
      proxy.initialize(params).get(30, TimeUnit.SECONDS)
    }
    assertNotNull(result)
    assertEquals("intellij-scala-lsp", result.getServerInfo.getName)
    socket.close()

  private def requestOffEdt[T](timeout: Int = 15)(body: => T): T =
    val future = CompletableFuture.supplyAsync[T](() => body)
    val deadline = System.currentTimeMillis() + timeout * 1000L
    while !future.isDone && System.currentTimeMillis() < deadline do
      com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    future.get(1, TimeUnit.SECONDS)

  def testStopProtocolShutsDaemonDown(): Unit =
    val socket = new Socket("localhost", TEST_PORT)
    val out = socket.getOutputStream
    val shutdownMsg = """{"jsonrpc":"2.0","id":1,"method":"shutdown","params":null}"""
    val header = s"Content-Length: ${shutdownMsg.length}\r\n\r\n"
    out.write(header.getBytes)
    out.write(shutdownMsg.getBytes)
    out.flush()
    socket.close()
    // Wait for daemon to stop
    daemonThread.join(5000)
    assertFalse("Daemon thread should have stopped", daemonThread.isAlive)
```

Note: `ProjectRegistry` needs a `registerForTesting(path, project)` method for test injection. Add:
```scala
/** For testing — register a pre-opened project */
private[scalalsP] def registerForTesting(path: String, project: Project): Unit =
  projects.put(canonicalize(path), project)
```

- [ ] **Step 2: Run test**

Run: `sbt 'lsp-server/testOnly org.jetbrains.scalalsP.integration.DaemonServerIntegrationTest'`
Expected: PASS

- [ ] **Step 3: Run all tests**

Run: `sbt 'lsp-server/test'`
Expected: All tests pass (233 existing + new daemon test)

- [ ] **Step 4: Commit**

```bash
git add lsp-server/test/src/integration/DaemonServerIntegrationTest.scala \
  lsp-server/src/org/jetbrains/scalalsP/ProjectRegistry.scala
git commit -m "test: add DaemonServer integration test"
```

---

### Task 9: Package, rebuild, and verify end-to-end

Build the artifact, test daemon mode against the real osiris project.

**Files:**
- No new files

- [ ] **Step 1: Build artifact**

```bash
sbt 'lsp-server/packageArtifact'
rm -f ~/.cache/intellij-scala-lsp/lsp-server-stripped.jar
```

- [ ] **Step 2: Test daemon mode manually**

```bash
# Start daemon with pre-warmed osiris
pkill -f ScalaLspMain; sleep 1
bash launcher/launch-lsp.sh --daemon /Users/hoangmei/Work/osiris
# (wait for "Listening on port 5007" in output)
```

In another terminal:
```python
# Connect and test hover
python3 /tmp/lsp-verify2.py  # reuse existing test script, or use socat
```

- [ ] **Step 3: Test auto-start mode**

```bash
pkill -f ScalaLspMain; sleep 1
rm -f ~/.cache/intellij-scala-lsp/daemon.pid ~/.cache/intellij-scala-lsp/daemon.port
# This should auto-start the daemon
echo '...' | bash launcher/launch-lsp.sh /Users/hoangmei/Work/osiris
```

- [ ] **Step 4: Test stop mode**

```bash
bash launcher/launch-lsp.sh --stop
# Verify daemon is gone
ps aux | grep ScalaLspMain
```

- [ ] **Step 5: Commit all remaining changes**

```bash
git add -A
git commit -m "feat: daemon launcher for multi-project LSP server

Persistent TCP daemon that bootstraps IntelliJ once and serves
multiple Claude Code sessions. Eliminates 25s cold start.

- DaemonServer: TCP accept, initialize intercept, session routing
- ProjectRegistry: thread-safe shared project management
- BootstrapState: shared latch for stdio/daemon/test modes
- ScalaLspMain: --daemon, --stop, auto-start connect modes
- launch-lsp.sh: daemon management, socat proxy
- ScalaLspServer: daemonMode guards shutdown/exit"
```
