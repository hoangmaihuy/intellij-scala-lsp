# Direct IntelliJ Platform Bootstrap for LSP Server

**Date:** 2026-03-19
**Status:** Draft

## Problem

IntelliJ 2025.3 maintains a hardcoded list of "well-known commands" in `WellKnownCommands.kt`. Commands not in this list are treated as `CommandType.GUI`. Our `scala-lsp` command is not in this list, so IntelliJ rejects it in headless mode.

The current launch path is:
```
launch-lsp.sh → java ... com.intellij.idea.Main scala-lsp <projectPath>
  → AppMode.setFlags() → WellKnownCommands.getCommandFor("scala-lsp") → null → treated as GUI
  → createAppStarter() → finds our ApplicationStarter, but headless/GUI mismatch causes failure
```

## Solution

Bypass `com.intellij.idea.Main` entirely. Bootstrap IntelliJ's `ApplicationImpl` directly in `ScalaLspMain`, using the same approach as IntelliJ's test framework (`testApplication.kt`). Then start the LSP server on the already-initialized platform.

## Design

### Bootstrap Sequence (ScalaLspMain)

The new `ScalaLspMain.main()` performs these steps in order:

1. **Set system properties** — `java.awt.headless=true`, `idea.is.internal=false`
2. **Set AppMode flags** — `AppMode.setHeadlessInTestMode(true)` (sets `isHeadless=true`, `isCommandLine=true`, `isLightEdit=false`). This is a `@TestOnly` API but only sets 3 boolean flags. Acceptable for our non-standard headless launch.
3. **Setup ForkJoin pool** — `IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true)` to configure IntelliJ's custom ForkJoinPool worker thread factory.
4. **Schedule plugin loading** — `PluginManagerCore.scheduleDescriptorLoading(GlobalScope)`. Note: `PluginManagerCore.isUnitTestMode` is left as `false` (the default) since this is a production server, not tests.
5. **Create ApplicationImpl** — Using the **production constructor**: `ApplicationImpl(CoroutineScope, isInternal=false)`. The production constructor reads `AppMode.isHeadless()` (set in step 2) and does NOT set `myTestModeFlag` or disable saving. We create a simple `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — no Fleet kernel needed since IntelliJ Community doesn't use it.
6. **Register components** — Wait for plugin loading to complete (with 60-second timeout), then `app.registerComponents(pluginSet.getEnabledModules(), app)`. Verify `ApplicationManager.getApplication()` is non-null after this.
7. **Initialize services** — In order:
   - `initConfigurationStore(app, emptyList())`
   - `RegistryManager.getInstance()` then `Registry.markAsLoaded()` (needed for `Registry.is()`/`Registry.get()` calls throughout IntelliJ)
   - `preloadCriticalServices(...)` + call `AppInitializedListener` callbacks
8. **Set loading state** — `LoadingState.setCurrentState(LoadingState.COMPONENTS_LOADED)` then `LoadingState.setCurrentState(LoadingState.APP_STARTED)`
9. **Start LSP server** — Extract project path from args, create `ScalaLspServer`, wire up lsp4j `Launcher`, block on `launcher.startListening().get()`

### Code Structure

```scala
object ScalaLspMain:
  def main(args: Array[String]): Unit =
    val projectPath = args.headOption.getOrElse {
      System.err.println("[ScalaLsp] ERROR: No project path. Usage: scala-lsp <projectPath>")
      System.exit(1); return
    }

    // 1-2. System properties and AppMode
    System.setProperty("java.awt.headless", "true")
    System.setProperty("idea.is.internal", "false")
    AppMode.setHeadlessInTestMode(true)

    // 3. ForkJoin pool
    IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true)

    // 4. Plugin loading
    PluginManagerCore.scheduleDescriptorLoading(GlobalScope)

    // 5-8. Bootstrap ApplicationImpl
    bootstrapIntellijPlatform()

    // 9. Start LSP
    startLspServer(projectPath, System.in, System.out)

  private def startLspServer(projectPath: String, in: InputStream, out: OutputStream): Unit =
    // Moved from ScalaLspApplicationStarter — creates ScalaLspServer,
    // wires lsp4j Launcher, blocks on startListening().get()
    ...
```

The `bootstrapIntellijPlatform()` method mirrors `loadAppInUnitTestMode()` from `testApplication.kt`, with these differences from the test version:
- Uses **production** `ApplicationImpl(CoroutineScope, isInternal)` constructor (not test constructor)
- Skips test-specific code: EDT busy thread, `PluginManagerCore.isUnitTestMode`, `Disposer.setDebugMode`, `Logger.setUnitTestMode`, `BundleBase.assertOnMissedKeys`, `RecursionManager.assertOnMissedCache`, `PersistentFS.cleanPersistedContents`
- Adds `RegistryManager.getInstance()` + `Registry.markAsLoaded()` between config store init and service preloading

The `startLspServer` method is moved from `ScalaLspApplicationStarter` into `ScalaLspMain` (or extracted to a shared utility).

### Launcher Script Change

```bash
# Before:
exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" "$MAIN_CLASS" scala-lsp "$@"

# After:
exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CLASSPATH" org.jetbrains.scalalsP.ScalaLspMain "$@"
```

The `$MAIN_CLASS` variable (read from `product-info.json`) and the `scala-lsp` command prefix are no longer needed.

### ScalaLspApplicationStarter

Kept as-is. It remains registered in `plugin.xml` but is not used by the new launch path. Removing it would be a separate cleanup.

### Build Changes

`AppMode.setHeadlessInTestMode()`, `PluginManagerCore`, `ApplicationImpl`, and related bootstrap APIs are in IntelliJ's platform JARs. `AppMode` is in `core-api`, `ApplicationImpl` is in `platform-impl`, both already on our classpath.

The bootstrap helper functions (`initConfigurationStore`, `preloadCriticalServices`, `callAppInitialized`/`getAppInitializedListeners`) live in `platform-impl/bootstrap/src/`. This is a separate compilation module that may be packaged as its own JAR or merged into `platform-impl.jar`. **Before implementation, verify:** check whether `$IDEA_HOME/lib/` contains a separate bootstrap JAR (e.g., `platform-bootstrap.jar`) or whether these classes are inside `platform-impl.jar`. If separate, the launcher script's `$IDEA_HOME/lib/*.jar` glob already includes it. If these classes are not accessible at runtime, we'll need to either:
- Confirm the JAR is on the classpath (most likely — `launch-lsp.sh` adds all `$IDEA_HOME/lib/*.jar`)
- Or replicate the essential initialization inline

### Threading Model

- Bootstrap runs on the main thread via `runBlocking(Dispatchers.Default)`
- After bootstrap, the main thread blocks on `launcher.startListening().get()` (lsp4j's JSON-RPC loop)
- All IntelliJ PSI access continues to use `ReadAction.compute()` and `ApplicationManager.getApplication.invokeAndWait()` as before

### Shutdown

When the LSP connection closes, `launcher.startListening().get()` returns, and we call `System.exit(0)`. No graceful `ApplicationImpl.dispose()` — the process is done.

### Error Handling

If bootstrap fails (plugin loading timeout, component registration error), log to stderr and `System.exit(1)`. Plugin loading uses a 60-second timeout to avoid silent hangs.

## Files Changed

| File | Change |
|------|--------|
| `lsp-server/src/ScalaLspMain.scala` | Full rewrite — direct ApplicationImpl bootstrap, `startLspServer` moved here |
| `launcher/launch-lsp.sh` | Change main class, drop `scala-lsp` command arg |
| `lsp-server/src/ScalaLspApplicationStarter.scala` | No change (kept but unused by new path) |
| `build.sbt` | Possibly no change; verify bootstrap APIs are accessible |

## Risks

1. **`@TestOnly` API usage** — `AppMode.setHeadlessInTestMode()` is marked `@TestOnly`. Accepted trade-off; it sets 3 boolean flags with no side effects.
2. **Internal API coupling** — `ApplicationImpl` constructor, `registerComponents`, `initConfigurationStore`, `preloadCriticalServices` are `@ApiStatus.Internal`. These may change across IntelliJ versions. Mitigated by pinning to a specific IntelliJ build in `build.sbt`.
3. **Missing kernel** — Skipping Fleet's `startServerKernel()`. If any platform service expects kernel coroutine context, it could fail. Mitigated by the fact that IntelliJ Community doesn't use Fleet kernel in production, and we use the production constructor which doesn't require it.
4. **Bootstrap module accessibility** — Some helper functions (`initConfigurationStore`, `preloadCriticalServices`, `callAppInitialized`) live in `platform-impl/bootstrap`. Must verify they're on the runtime classpath before implementation.
5. **Project opening** — `ProjectManager.getInstance().loadAndOpenProject()` depends on the full initialization chain. This already works in our test suite (which uses a similar bootstrap), but should be verified with the production constructor path.
