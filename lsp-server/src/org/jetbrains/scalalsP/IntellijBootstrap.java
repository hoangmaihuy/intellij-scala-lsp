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
import com.intellij.openapi.project.impl.P3SupportInstaller;
import com.intellij.platform.ide.bootstrap.ApplicationLoader;
import com.intellij.platform.ide.bootstrap.AppServicePreloadingKt;
import com.intellij.platform.ide.bootstrap.kernel.KernelKt;
import com.intellij.platform.ide.bootstrap.kernel.KernelStarted;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.*;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps IntelliJ's ApplicationImpl directly in headless mode.
 * <p>
 * This bypasses {@code com.intellij.idea.Main} because IntelliJ 2025.3's
 * {@code WellKnownCommands} rejects custom commands in headless mode.
 * <p>
 * The bootstrap sequence mirrors the test framework's {@code loadAppInUnitTestMode()}.
 */
public final class IntellijBootstrap {

    private IntellijBootstrap() {}

    /**
     * Initialize the IntelliJ platform in headless mode.
     * Called once from ScalaLspMain's background bootstrap thread.
     */
    public static void initialize() throws Exception {
        System.err.println("[IntellijBootstrap] Setting system properties...");

        // Step 1: Set system properties
        System.setProperty("java.awt.headless", "true");
        System.setProperty("idea.is.internal", "false");

        // Set JNA boot library path if not already set (needed for native file watching etc.)
        if (System.getProperty("jna.boot.library.path") == null) {
            String homePath = com.intellij.openapi.application.PathManager.getHomePath();
            String arch = System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm64")
                ? "aarch64" : "amd64";
            String jnaPath = homePath + "/lib/jna/" + arch;
            if (new java.io.File(jnaPath).isDirectory()) {
                System.setProperty("jna.boot.library.path", jnaPath);
                System.err.println("[IntellijBootstrap] JNA native library path: " + jnaPath);
            }
        }

        // Step 2: Set headless test mode (sets 3 internal boolean flags)
        AppMode.setHeadlessInTestMode(true);

        // Step 3: Setup ForkJoinPool and seal P3 support
        IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true);
        P3SupportInstaller.INSTANCE.seal();

        // Step 4: Start the Fleet kernel (provides rhizomedb transactor on coroutine context)
        System.err.println("[IntellijBootstrap] Starting kernel...");
        CoroutineScope globalScope = GlobalScope.INSTANCE;
        KernelStarted kernelStarted = (KernelStarted) BuildersKt.runBlocking(
            Dispatchers.getDefault(),
            (Function2<CoroutineScope, Continuation<? super KernelStarted>, Object>) (scope, continuation) ->
                KernelKt.startServerKernel(globalScope, continuation)
        );

        // Step 5: Create coroutine scope with kernel context
        CompletableJob supervisorJob = SupervisorKt.SupervisorJob(null);
        CoroutineContext context = supervisorJob
            .plus(Dispatchers.getDefault())
            .plus(kernelStarted.getCoroutineContext());
        CoroutineScope appScope = CoroutineScopeKt.CoroutineScope(context);

        // Step 6: Schedule plugin descriptor loading
        System.err.println("[IntellijBootstrap] Loading plugins...");
        PluginManagerCore.INSTANCE.scheduleDescriptorLoading(appScope);

        // Step 7: Create ApplicationImpl using the production constructor
        System.err.println("[IntellijBootstrap] Creating ApplicationImpl...");
        ApplicationImpl app = new ApplicationImpl(appScope, false);

        // Step 8: Wait for plugins and register components
        System.err.println("[IntellijBootstrap] Waiting for plugin set...");
        PluginSet pluginSet = waitForPluginSet(60, TimeUnit.SECONDS);
        System.err.println("[IntellijBootstrap] Registering components...");
        app.registerComponents(pluginSet.getEnabledModules(), app, Collections.emptyList());

        // Step 9: Set the application in ApplicationManager
        ApplicationManager.setApplication(app);
        assert ApplicationManager.getApplication() != null : "Application must not be null after setApplication";

        // Step 10: Initialize services using runBlocking for suspend function interop
        System.err.println("[IntellijBootstrap] Initializing configuration store...");
        BuildersKt.runBlocking(
            appScope.getCoroutineContext(),
            (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (scope, continuation) ->
                ApplicationLoader.initConfigurationStore(app, Collections.emptyList(), continuation)
        );

        // Step 11: Initialize Registry
        System.err.println("[IntellijBootstrap] Initializing registry...");
        RegistryManager.getInstance();
        Registry.markAsLoaded();

        // Step 12: Preload critical services
        System.err.println("[IntellijBootstrap] Preloading critical services...");
        CompletableDeferred<Object> appRegistered = CompletableDeferredKt.CompletableDeferred(null);
        appRegistered.complete(null);
        Job preloadJob = AppServicePreloadingKt.preloadCriticalServices(
            app,
            appScope,         // preloadScope
            app.getCoroutineScope(), // asyncScope
            appRegistered,    // appRegistered (completed Job)
            null              // initAwtToolkitAndEventQueueJob
        );
        // Wait for preload to finish
        BuildersKt.runBlocking(
            appScope.getCoroutineContext(),
            (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (scope, continuation) ->
                preloadJob.join(continuation)
        );

        // Step 13: Call app initialized listeners
        System.err.println("[IntellijBootstrap] Calling app initialized listeners...");
        ApplicationLoader.callAppInitialized(appScope, ApplicationLoader.getAppInitializedListeners(app));

        // Step 14: Set loading states
        LoadingState.setCurrentState(LoadingState.COMPONENTS_LOADED);
        LoadingState.setCurrentState(LoadingState.APP_STARTED);

        System.err.println("[IntellijBootstrap] IntelliJ platform initialized successfully");
    }

    /**
     * Waits for the plugin set to become available.
     */
    private static PluginSet waitForPluginSet(long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            PluginSet pluginSet = PluginManagerCore.getPluginSetOrNull();
            if (pluginSet != null) {
                return pluginSet;
            }
            //noinspection BusyWait
            Thread.sleep(100);
        }
        throw new RuntimeException("Timed out waiting for plugin descriptors to load after " + timeout + " " + unit);
    }
}
