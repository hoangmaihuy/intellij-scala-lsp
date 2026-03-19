# Direct IntelliJ Platform Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite ScalaLspMain to directly bootstrap IntelliJ's ApplicationImpl in headless mode, bypassing com.intellij.idea.Main which rejects our custom "scala-lsp" command.

**Architecture:** ScalaLspMain becomes the JVM entry point. It calls a Java helper (`IntellijBootstrap.java`) that sets headless flags, loads plugins, creates ApplicationImpl (production constructor), registers components, initializes services, then returns. ScalaLspMain then starts the LSP server. The launcher script calls our main class directly instead of com.intellij.idea.Main.

**Tech Stack:** Scala 3.8.2, Java 21, IntelliJ Platform SDK 253.32098.37 (Community), lsp4j 0.23.1, sbt-idea-plugin

**Spec:** `docs/superpowers/specs/2026-03-19-direct-bootstrap-design.md`

---

### Task 1: Create Java bootstrap helper

**Files:**
- Create: `lsp-server/src/org/jetbrains/scalalsP/IntellijBootstrap.java`

The bootstrap is written in Java (not Kotlin) to avoid adding Kotlin compilation to sbt. All the IntelliJ APIs we need are callable from Java — the only Kotlin-specific construct (`runBlocking`) is a regular function callable from Java.

**Key IntelliJ APIs used (all in `app.jar` under `$IDEA_HOME/lib/`):**
- `com.intellij.idea.AppMode.setHeadlessInTestMode(boolean)` — `@TestOnly`, sets 3 flags
- `com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(boolean)`
- `com.intellij.ide.plugins.PluginManagerCore.scheduleDescriptorLoading(CoroutineScope)`
- `com.intellij.ide.plugins.PluginManagerCore.initPluginFuture` — `Deferred<PluginSet>`
- `com.intellij.openapi.application.impl.ApplicationImpl(CoroutineScope, boolean)` — production constructor
- `ApplicationImpl.registerComponents(modules, app)`
- `com.intellij.openapi.application.ApplicationManager.setApplication(Application)`
- `com.intellij.platform.ide.bootstrap.ApplicationLoader` — `initConfigurationStore` (suspend), `getAppInitializedListeners`, `callAppInitialized`
- `com.intellij.platform.ide.bootstrap.AppServicePreloadingKt.preloadCriticalServices(...)` — returns Job
- `com.intellij.openapi.util.registry.RegistryManager.getInstance()`
- `com.intellij.openapi.util.registry.Registry.markAsLoaded()`
- `com.intellij.diagnostic.LoadingState.setCurrentState(LoadingState)`

**Important details from IntelliJ source analysis:**
- Production constructor reads `AppMode.isHeadless()` and `AppMode.isCommandLine()` — so `AppMode.setHeadlessInTestMode(true)` MUST be called before constructing ApplicationImpl.
- Production constructor does NOT call `postInit(app)` or `ApplicationManager.setApplication(app)`. We must call `ApplicationManager.setApplication(app)` after `registerComponents`.
- `postInit(app)` is called inside `preloadCriticalServices` → `postAppRegistered`, but ONLY when `initAwtToolkitAndEventQueueJob != null`. In headless mode we pass `null`, so `postInit` is correctly skipped (it sets up Swing event queue processors not needed headlessly).
- `preloadCriticalServices` takes an `appRegistered: Job` parameter. Pass `CompletableDeferred(value = null)` (already-completed job), same as the test framework does.
- `RegistryManager.getInstance()` and `Registry.markAsLoaded()` must be called explicitly between `initConfigurationStore` and `preloadCriticalServices`, matching the test framework's `testApplication.kt` lines 209-210. `preloadCriticalServices` only launches an async job to preload the RegistryManager service — it does NOT call `markAsLoaded()`.
- `preloadCriticalServices` launches background coroutines on `asyncScope` (app's scope) that continue after `runBlocking` returns. This is fine — `ScalaLspServer.initialize()` already calls `waitForSmartMode()` which waits for indexing (which depends on those services).

- [ ] **Step 1: Write IntellijBootstrap.java**

Create `lsp-server/src/org/jetbrains/scalalsP/IntellijBootstrap.java`:

```java
package org.jetbrains.scalalsP;

import com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory;
import com.intellij.diagnostic.LoadingState;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.PluginSet;
import com.intellij.idea.AppMode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.ApplicationImpl;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryManager;
import com.intellij.platform.ide.bootstrap.ApplicationLoaderKt;
import com.intellij.platform.ide.bootstrap.AppServicePreloadingKt;
import kotlinx.coroutines.*;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps IntelliJ platform directly, bypassing com.intellij.idea.Main.
 * <p>
 * IntelliJ 2025.3's WellKnownCommands rejects custom appStarter commands in headless mode.
 * This bootstrap mirrors the test framework's loadAppInUnitTestMode() from testApplication.kt,
 * adapted for production use (uses production ApplicationImpl constructor, no test-mode flags).
 */
public final class IntellijBootstrap {

    public static void initialize() {
        // 1. System properties
        System.setProperty("java.awt.headless", "true");
        System.setProperty("idea.is.internal", "false");

        // 2. AppMode flags — must be set before ApplicationImpl constructor reads them
        AppMode.setHeadlessInTestMode(true);

        // 3. ForkJoin pool setup (needed for coroutine dispatchers using common pool)
        IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true);

        // 4. Schedule plugin descriptor loading
        PluginManagerCore.scheduleDescriptorLoading(GlobalScope.INSTANCE);

        // 5-8. Create app, register components, initialize services
        loadApp();
    }

    private static void loadApp() {
        // 5. Create ApplicationImpl with production constructor
        CoroutineScope appScope = CoroutineScopeKt.CoroutineScope(
            SupervisorKt.SupervisorJob(null).plus(Dispatchers.getDefault())
        );
        ApplicationImpl app = new ApplicationImpl(appScope, /* isInternal = */ false);

        // 6. Wait for plugins to load (60s timeout to avoid silent hang)
        PluginSet pluginSet;
        try {
            pluginSet = FutureKt.asCompletableFuture(PluginManagerCore.getInitPluginFuture())
                .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load plugins within 60 seconds", e);
        }
        app.registerComponents(pluginSet.getEnabledModules(), app);
        ApplicationManager.setApplication(app);

        assert ApplicationManager.getApplication() != null : "ApplicationManager.getApplication() is null after setApplication";

        // 7. Initialize services via runBlocking
        BuildersKt.runBlocking(
            Dispatchers.getDefault(),
            (scope, continuation) -> {
                // Init config store (suspend function)
                Object result = ApplicationLoaderKt.initConfigurationStore(app, Collections.emptyList(), continuation);
                if (result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                    return result;
                }
                return initServicesAfterConfigStore(app, scope, continuation);
            }
        );
    }

    // Called after initConfigurationStore completes (it's a suspend continuation)
    private static Object initServicesAfterConfigStore(
            ApplicationImpl app, CoroutineScope scope, Continuation<? super Unit> continuation) {
        // Registry must be initialized before preloadCriticalServices (see testApplication.kt:209-210)
        RegistryManager.getInstance();
        Registry.markAsLoaded();

        // Preload critical services
        // appRegistered = already-completed job, initAwtToolkitAndEventQueueJob = null (headless)
        CompletableDeferred<Object> appRegistered = CompletableDeferredKt.CompletableDeferred(null);
        AppServicePreloadingKt.preloadCriticalServices(
            app,
            scope,
            app.getCoroutineScope(),
            appRegistered,
            null  // headless — skip postInit/AWT setup
        );

        // Call ApplicationInitializedListener callbacks
        ApplicationLoaderKt.callAppInitialized(
            scope,
            ApplicationLoaderKt.getAppInitializedListeners(app)
        );

        LoadingState.setCurrentState(LoadingState.COMPONENTS_LOADED);
        LoadingState.setCurrentState(LoadingState.APP_STARTED);

        return Unit.INSTANCE;
    }
}
```

**Note on suspend function interop:** `initConfigurationStore` is a Kotlin `suspend` function. Calling it from Java requires handling the `Continuation` parameter and the `COROUTINE_SUSPENDED` sentinel value. The `runBlocking` lambda receives a `Continuation`. If `initConfigurationStore` suspends, the coroutine machinery handles resumption automatically within `runBlocking`. The continuation-based approach above may need adjustment at compile time — if the suspend interop is too complex, wrap the call in a Kotlin utility function (a single `.kt` file with just `fun initConfigStoreBlocking(app, args)` that calls the suspend function inside `runBlocking`).

**Practical fallback:** If Java suspend interop proves too painful, create a minimal `BootstrapCoroutines.kt` file with a single blocking wrapper function and call that from Java. This limits Kotlin to one small file.

- [ ] **Step 2: Add Java source directory to build.sbt**

The project's source layout uses `lsp-server/src/` for Scala files. Java files need to be on the source path too. Check if sbt already picks up `.java` files from the same source directory. If not, add:

```scala
Compile / unmanagedSourceDirectories += baseDirectory.value / "src"
```

This may already be configured since `Compile / unmanagedSourceDirectories := Seq((Compile / sourceDirectory).value)` and `Compile / sourceDirectory := baseDirectory.value / "src"` — sbt compiles both `.scala` and `.java` from the same source directories by default.

- [ ] **Step 3: Compile and verify**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log/compile.log`

Expected issues to fix:
- `@TestOnly` warning on `AppMode.setHeadlessInTestMode` — add `@SuppressWarnings` in Java
- Kotlin function name mangling — top-level Kotlin functions compile to `<FileName>Kt.functionName()`. Verify the actual class names: `ApplicationLoaderKt`, `AppServicePreloadingKt`, `CompletableDeferredKt`, etc.
- `Deferred.asCompletableFuture()` — this is an extension function in `kotlinx.coroutines.future`. From Java, call `FutureKt.asCompletableFuture(deferred)`. The `kotlinx-coroutines-jdk8` JAR must be on the classpath (it's in IntelliJ's `lib/` directory).
- Suspend function continuation interop — if the continuation-based approach fails, fall back to creating a small Kotlin wrapper file.

- [ ] **Step 4: Commit**

```bash
git add lsp-server/src/org/jetbrains/scalalsP/IntellijBootstrap.java
git commit -m "feat: add Java bootstrap helper for direct IntelliJ platform initialization

Bootstraps ApplicationImpl directly, bypassing com.intellij.idea.Main
which rejects custom appStarter commands in headless mode (IntelliJ 2025.3
WellKnownCommands hardcoded list). Mirrors test framework approach."
```

---

### Task 2: Rewrite ScalaLspMain to use bootstrap helper

**Files:**
- Modify: `lsp-server/src/ScalaLspMain.scala`

- [ ] **Step 1: Rewrite ScalaLspMain**

```scala
package org.jetbrains.scalalsP

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient

import java.io.{InputStream, OutputStream}

/**
 * Entry point for the IntelliJ Scala LSP server.
 *
 * Bootstraps the IntelliJ platform directly (bypassing com.intellij.idea.Main)
 * because IntelliJ 2025.3's WellKnownCommands rejects custom commands in headless mode.
 */
object ScalaLspMain:

  def main(args: Array[String]): Unit =
    val projectPath = args.headOption.getOrElse {
      System.err.println("[ScalaLsp] ERROR: No project path provided. Usage: scala-lsp <projectPath>")
      System.exit(1)
      return
    }

    System.err.println(s"[ScalaLsp] Starting IntelliJ Scala LSP server for project: $projectPath")

    try
      IntellijBootstrap.initialize()
      System.err.println("[ScalaLsp] IntelliJ platform initialized")
      startLspServer(projectPath, System.in, System.out)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Fatal error: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)

  private def startLspServer(projectPath: String, in: InputStream, out: OutputStream): Unit =
    val server = new ScalaLspServer(projectPath)

    val launcher = new Launcher.Builder[LanguageClient]()
      .setLocalService(server)
      .setRemoteInterface(classOf[LanguageClient])
      .setInput(in)
      .setOutput(out)
      .create()

    val client = launcher.getRemoteProxy
    server.connect(client)

    System.err.println("[ScalaLsp] LSP server started, listening on stdin/stdout")
    launcher.startListening().get()

    System.err.println("[ScalaLsp] LSP connection closed")
    System.exit(0)
```

- [ ] **Step 2: Compile**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log/compile.log`

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/ScalaLspMain.scala
git commit -m "feat: rewrite ScalaLspMain to use direct IntelliJ bootstrap

Calls IntellijBootstrap.initialize() instead of delegating to
com.intellij.idea.Main. Moves LSP server startup logic from
ScalaLspApplicationStarter into ScalaLspMain."
```

---

### Task 3: Update launcher script

**Files:**
- Modify: `launcher/launch-lsp.sh`

- [ ] **Step 1: Change the exec line to use ScalaLspMain**

In `launcher/launch-lsp.sh`, change the final `exec` line (line 235-238):

```bash
# Before:
exec "$JAVA" \
  "${JVM_ARGS[@]}" \
  -cp "$CLASSPATH" \
  "$MAIN_CLASS" scala-lsp "$@"

# After:
exec "$JAVA" \
  "${JVM_ARGS[@]}" \
  -cp "$CLASSPATH" \
  org.jetbrains.scalalsP.ScalaLspMain "$@"
```

The `$MAIN_CLASS` variable (from product-info.json) is no longer needed for the exec line, but keep the `read_product_info` function since we still need boot classpath and JVM args from it.

- [ ] **Step 2: Commit**

```bash
git add launcher/launch-lsp.sh
git commit -m "fix: update launcher to use ScalaLspMain instead of Main

ScalaLspMain bootstraps the IntelliJ platform directly, so we no longer
need to go through com.intellij.idea.Main with the scala-lsp command."
```

---

### Task 4: Verify bootstrap works

**Files:** None (testing only)

- [ ] **Step 1: Run existing tests to verify no regressions**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log/test.log`

Tests use `BasePlatformTestCase` which has its own bootstrap — they should be unaffected by changes to ScalaLspMain.

- [ ] **Step 2: Package the plugin**

Run: `sbt "lsp-server/packageArtifact" 2>&1 | tee /local/log/package.log`

- [ ] **Step 3: Test the launcher**

Run the launcher script against a test Scala project to verify the full bootstrap-to-LSP flow works:

```bash
./launcher/launch-lsp.sh /path/to/a/scala/project 2>/local/log/lsp-stderr.log
```

Check stderr log for:
- `[ScalaLsp] IntelliJ platform initialized` — bootstrap succeeded
- `[ScalaLsp] LSP server started, listening on stdin/stdout` — LSP server started
- No stack traces or fatal errors

Send a basic LSP initialize request on stdin to verify the server responds.

- [ ] **Step 4: Commit any fixes**

If fixes were needed, commit them.
