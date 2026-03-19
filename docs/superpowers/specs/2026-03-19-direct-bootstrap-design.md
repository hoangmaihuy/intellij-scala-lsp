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
3. **Schedule plugin loading** — `PluginManagerCore.scheduleDescriptorLoading(GlobalScope)`
4. **Create ApplicationImpl** — Using a `CoroutineScope(Dispatchers.Default)` for the coroutine context. We skip `startServerKernel()` (Fleet's kernel) since it depends on `fleet.kernel` which is not on IntelliJ Community's runtime classpath.
5. **Register components** — Wait for plugin loading to complete, then `app.registerComponents(pluginSet.getEnabledModules(), app)`
6. **Initialize services** — `initConfigurationStore(app, emptyList())`, `preloadCriticalServices(...)`, call `AppInitializedListener` callbacks
7. **Set loading state** — `LoadingState.setCurrentState(LoadingState.APP_STARTED)`
8. **Start LSP server** — Extract project path from args, create `ScalaLspServer`, wire up lsp4j `Launcher`, block on `launcher.startListening().get()`

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

    // 3. Plugin loading
    PluginManagerCore.scheduleDescriptorLoading(GlobalScope)

    // 4-7. Bootstrap ApplicationImpl (see below)
    bootstrapIntellijPlatform()

    // 8. Start LSP
    startLspServer(projectPath, System.in, System.out)
```

The `bootstrapIntellijPlatform()` method mirrors `loadAppInUnitTestMode()` from `testApplication.kt`, minus test-specific code (EDT busy thread, debug mode, disposer debug, leak detection, bean cache clearing).

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

`AppMode.setHeadlessInTestMode()`, `PluginManagerCore`, `ApplicationImpl`, and related bootstrap APIs are in IntelliJ's platform JARs which are already on our classpath. The test framework common module (`testFramework/common`) provides some helpers but the core APIs we need are in `platform-impl`. No new dependencies should be required — `AppMode` is in `core-api`, `ApplicationImpl` and bootstrap functions are in `platform-impl`, both already on our classpath.

If `initConfigurationStore`, `preloadCriticalServices`, or `callAppInitialized`/`getAppInitializedListeners` are not accessible (they're in the `bootstrap` module which may not be on the runtime classpath), we'll need to either:
- Add the bootstrap module JAR to the classpath in the launcher script
- Or replicate the essential initialization inline (config store init + service preloading)

### Threading Model

- Bootstrap runs on the main thread via `runBlocking(Dispatchers.Default)`
- After bootstrap, the main thread blocks on `launcher.startListening().get()` (lsp4j's JSON-RPC loop)
- All IntelliJ PSI access continues to use `ReadAction.compute()` and `ApplicationManager.getApplication.invokeAndWait()` as before

### Shutdown

When the LSP connection closes, `launcher.startListening().get()` returns, and we call `System.exit(0)`. No graceful `ApplicationImpl.dispose()` — the process is done.

### Error Handling

If bootstrap fails (plugin loading timeout, component registration error), log to stderr and `System.exit(1)`. The launcher script captures stderr for diagnostics.

## Files Changed

| File | Change |
|------|--------|
| `lsp-server/src/ScalaLspMain.scala` | Full rewrite — direct ApplicationImpl bootstrap |
| `launcher/launch-lsp.sh` | Change main class, drop `scala-lsp` command arg |
| `lsp-server/src/ScalaLspApplicationStarter.scala` | No change (kept but unused by new path) |
| `build.sbt` | Possibly no change; verify bootstrap APIs are accessible |

## Risks

1. **`@TestOnly` API usage** — `AppMode.setHeadlessInTestMode()` is marked `@TestOnly`. Accepted trade-off; it sets 3 boolean flags with no side effects.
2. **Internal API coupling** — `ApplicationImpl` constructor, `registerComponents`, `initConfigurationStore`, `preloadCriticalServices` are `@ApiStatus.Internal`. These may change across IntelliJ versions. Mitigated by pinning to a specific IntelliJ build in `build.sbt`.
3. **Missing kernel** — Skipping Fleet's `startServerKernel()`. If any platform service expects kernel coroutine context, it could fail. Mitigated by the fact that IntelliJ Community doesn't use Fleet kernel in production.
4. **Bootstrap module accessibility** — Some helper functions (`initConfigurationStore`, `preloadCriticalServices`, `callAppInitialized`) live in `platform-impl/bootstrap`. If this module isn't on the runtime classpath, we'll need to add it or inline the logic.
