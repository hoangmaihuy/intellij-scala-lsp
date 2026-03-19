# Direct IntelliJ Platform Bootstrap for LSP Server

**Date:** 2026-03-19
**Status:** Implemented

## Problem

IntelliJ 2025.3 maintains a hardcoded list of "well-known commands" in `WellKnownCommands.kt`. Commands not in this list are treated as `CommandType.GUI`. Our `scala-lsp` command is not in this list, so IntelliJ rejects it in headless mode.

The previous launch path was:
```
launch-lsp.sh → java ... com.intellij.idea.Main scala-lsp <projectPath>
  → AppMode.setFlags() → WellKnownCommands.getCommandFor("scala-lsp") → null → treated as GUI
  → createAppStarter() → finds our ApplicationStarter, but headless/GUI mismatch causes failure
```

## Solution

Bypass `com.intellij.idea.Main` entirely. Bootstrap IntelliJ's `ApplicationImpl` directly via `IntellijBootstrap.java`, called from `ScalaLspMain.scala`. The bootstrap mirrors IntelliJ's test framework (`testApplication.kt`), adapted for production use.

## Implementation

### Architecture

```
ScalaLspMain.scala (entry point)
  → IntellijBootstrap.java (platform bootstrap)
  → LspLauncher.java (JSON-RPC setup, works around Scala 3 bridge methods)
  → ScalaLspServer.scala (LSP protocol handler)
```

The bootstrap is written in Java because it calls Kotlin suspend functions (`initConfigurationStore`) and avoids Scala 3 bridge method issues. The LSP Launcher is also in Java to work around lsp4j's reflection-based method scanning which conflicts with Scala 3's annotated bridge methods.

### Bootstrap Sequence (IntellijBootstrap.java)

1. **Set system properties** — `java.awt.headless=true`, `idea.is.internal=false`, `jna.boot.library.path`
2. **Set AppMode flags** — `AppMode.setHeadlessInTestMode(true)`. `@TestOnly` API that sets 3 boolean flags.
3. **Setup ForkJoin pool** — `IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true)`
4. **Seal P3 support** — `P3SupportInstaller.seal()` (required before `initConfigurationStore`)
5. **Start Fleet kernel** — `startServerKernel(GlobalScope)` provides the rhizomedb transactor on the coroutine context. Required by `ClientSessionImpl` during component registration.
6. **Create coroutine scope** — `CoroutineScope(SupervisorJob() + Dispatchers.Default + kernelContext)`
7. **Schedule plugin loading** — `PluginManagerCore.scheduleDescriptorLoading(appScope)`
8. **Create ApplicationImpl** — Production constructor: `ApplicationImpl(appScope, isInternal=false)`. Reads `AppMode.isHeadless()` from step 2.
9. **Register components** — Wait for plugins (60s timeout), `app.registerComponents(pluginSet.getEnabledModules(), app)`
10. **Set ApplicationManager** — `ApplicationManager.setApplication(app)`
11. **Initialize config store** — `initConfigurationStore(app, emptyList())` (Kotlin suspend, called via `runBlocking`)
12. **Initialize Registry** — `RegistryManager.getInstance()` + `Registry.markAsLoaded()`
13. **Preload services** — `preloadCriticalServices(app, appScope, app.getCoroutineScope(), completedJob, null)`
14. **Call listeners** — `callAppInitialized(appScope, getAppInitializedListeners(app))`
15. **Set loading state** — `COMPONENTS_LOADED` then `APP_STARTED`

### Key Differences from Original Spec

| Spec Assumption | Actual Implementation |
|---|---|
| Fleet kernel not needed | **Required** — `ClientSessionImpl` needs rhizomedb transactor on coroutine context |
| Kotlin bootstrap helper | **Java** — avoids sbt Kotlin compilation setup and suspend function interop issues |
| Simple `Launcher.Builder` | **Custom LspLauncher.java** — wraps server in Java class to avoid Scala 3 bridge method annotation duplication |
| `P3SupportInstaller.seal()` not mentioned | **Required** — called before `initConfigurationStore` |
| `idea.home.path` not set | **Required** — launcher must set it for JNA and other path resolution |

### Scala 3 Bridge Method Issue (LspLauncher.java)

Scala 3 generates bridge methods for inherited default interface methods that copy `@JsonNotification`/`@JsonRequest` annotations. When lsp4j's `ServiceEndpoints` scans a Scala class via reflection, it finds duplicate RPC method names (e.g., `window/workDoneProgress/cancel` on both the bridge method and the original interface method).

**Fix:** `LspLauncher.java` wraps `ScalaLspServer` and its delegate services (`TextDocumentService`, `WorkspaceService`) in pure Java classes. It overrides `Launcher.Builder.getSupportedMethods()` to return pre-computed methods from Java interfaces, and overrides `createRemoteEndpoint()` to use a Java-wrapped endpoint. This completely bypasses reflection on Scala implementation classes.

### BSP Auto-Import (IntellijProjectManager.scala)

After opening a project, if a `.bsp` directory exists, the project manager attempts to link the BSP project via `BspOpenProjectProvider.doLinkProject()` using reflection (since BSP classes are in the Scala plugin, not on the compile classpath).

**Current limitation:** BSP linking fails with `ClassNotFoundException` because the Scala plugin's classes aren't accessible via the system classloader in the flat-classpath launch mode. The project opens but without BSP module structure. This is a follow-up item.

### Launcher Script (launch-lsp.sh)

- Calls `org.jetbrains.scalalsP.ScalaLspMain` directly instead of `com.intellij.idea.Main scala-lsp`
- Sets `-Didea.home.path=$IDEA_HOME` for path resolution
- Resolves `%IDE_HOME%` and `$IDE_HOME` placeholders in product-info.json JVM args (needed for JNA native library path on macOS)
- Still reads product-info.json for boot classpath and JVM args

### Build Changes (build.sbt)

- Added `javacOptions ++= Seq("--release", "21")` — IntelliJ's bundled JBR is Java 21; compilation with newer JDKs must target 21
- lsp4j version remains 0.23.1

## Files Changed

| File | Change |
|------|--------|
| `lsp-server/src/ScalaLspMain.scala` | Rewritten — calls `IntellijBootstrap.initialize()` then `LspLauncher.startAndAwait()` |
| `lsp-server/src/org/jetbrains/scalalsP/IntellijBootstrap.java` | New — direct ApplicationImpl bootstrap |
| `lsp-server/src/org/jetbrains/scalalsP/LspLauncher.java` | New — lsp4j Launcher with Java wrappers to avoid Scala 3 bridge methods |
| `lsp-server/src/intellij/IntellijProjectManager.scala` | Added BSP auto-import detection |
| `launcher/launch-lsp.sh` | Changed main class, added `idea.home.path`, fixed `%IDE_HOME%` resolution |
| `build.sbt` | Added `javacOptions --release 21` |
| `lsp-server/src/ScalaLspApplicationStarter.scala` | No change (kept but unused by new path) |
| `lsp-server/test/src/integration/LspLauncherIntegrationTest.scala` | New — verifies no bridge method duplicate errors |
| `lsp-server/test/src/org/jetbrains/scalalsP/JavaTestLanguageClient.java` | New — Java test client for lsp4j compatibility |

## Known Issues / Follow-ups

1. **BSP auto-import not working** — Scala plugin classes not accessible via system classloader in flat-classpath mode. Needs plugin classloader integration.
2. **`ScalaLspApplicationStarter` is dead code** — Kept in plugin.xml but unused. Should be removed or deprecated.
3. **`@TestOnly` API usage** — `AppMode.setHeadlessInTestMode()` is marked `@TestOnly`. Acceptable trade-off.
4. **Internal API coupling** — Bootstrap depends on `@ApiStatus.Internal` APIs that may change across IntelliJ versions. Mitigated by pinning to a specific IntelliJ build.
