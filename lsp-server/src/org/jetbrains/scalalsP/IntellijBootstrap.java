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
import com.intellij.platform.ide.bootstrap.ApplicationLoader;
import com.intellij.platform.ide.bootstrap.AppServicePreloadingKt;

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
     * Must be called once before any IntelliJ APIs are used.
     */
    public static void initialize() throws Exception {
        System.err.println("[IntellijBootstrap] Setting system properties...");

        // Step 1: Set system properties
        System.setProperty("java.awt.headless", "true");
        System.setProperty("idea.is.internal", "false");

        // Step 2: Set headless test mode (sets 3 internal boolean flags)
        AppMode.setHeadlessInTestMode(true);

        // Step 3: Setup ForkJoinPool
        IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true);

        // Step 4: Create coroutine scope for the application
        CompletableJob supervisorJob = SupervisorKt.SupervisorJob(null);
        CoroutineContext context = supervisorJob.plus(Dispatchers.getDefault());
        CoroutineScope appScope = CoroutineScopeKt.CoroutineScope(context);

        // Step 5: Schedule plugin descriptor loading
        System.err.println("[IntellijBootstrap] Loading plugins...");
        PluginManagerCore.INSTANCE.scheduleDescriptorLoading(appScope);

        // Step 6: Create ApplicationImpl using the production constructor
        System.err.println("[IntellijBootstrap] Creating ApplicationImpl...");
        ApplicationImpl app = new ApplicationImpl(appScope, false);

        // Step 7: Wait for plugins and register components
        System.err.println("[IntellijBootstrap] Waiting for plugin set...");
        PluginSet pluginSet = waitForPluginSet(60, TimeUnit.SECONDS);
        System.err.println("[IntellijBootstrap] Registering components...");
        app.registerComponents(pluginSet.getEnabledModules(), app, Collections.emptyList());

        // Step 8: Set the application in ApplicationManager
        ApplicationManager.setApplication(app);
        assert ApplicationManager.getApplication() != null : "Application must not be null after setApplication";

        // Step 9: Initialize services using runBlocking for suspend function interop
        System.err.println("[IntellijBootstrap] Initializing configuration store...");
        BuildersKt.runBlocking(
            Dispatchers.getDefault(),
            (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (scope, continuation) ->
                ApplicationLoader.initConfigurationStore(app, Collections.emptyList(), continuation)
        );

        // Step 10: Initialize Registry
        System.err.println("[IntellijBootstrap] Initializing registry...");
        RegistryManager.getInstance();
        Registry.markAsLoaded();

        // Step 11: Preload critical services
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
            Dispatchers.getDefault(),
            (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (scope, continuation) ->
                preloadJob.join(continuation)
        );

        // Step 12: Call app initialized listeners
        System.err.println("[IntellijBootstrap] Calling app initialized listeners...");
        ApplicationLoader.callAppInitialized(appScope, ApplicationLoader.getAppInitializedListeners(app));

        // Step 13: Set loading states
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
