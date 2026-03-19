package org.jetbrains.scalalsP;

import com.intellij.testFramework.TestApplicationManager;

/**
 * Bootstraps IntelliJ's ApplicationImpl in headless mode using the test framework.
 * <p>
 * This uses {@link TestApplicationManager#getInstance()} which calls
 * {@code initTestApplication()} internally. It handles all the complex initialization:
 * EDT setup, VFS, plugin loading, kernel, component registration, service preloading, etc.
 * <p>
 * We use the test framework bootstrap because:
 * <ul>
 *   <li>It's proven to work (our 212 integration tests use it)</li>
 *   <li>Custom bootstrap hits edge cases with EDT, VFS locks, and headless mode</li>
 *   <li>The @TestOnly APIs we use are just setting boolean flags, acceptable trade-off</li>
 * </ul>
 */
public final class IntellijBootstrap {

    private IntellijBootstrap() {}

    /**
     * Initialize the IntelliJ platform in headless mode.
     * Called once from ScalaLspMain's background bootstrap thread.
     */
    public static void initialize() throws Exception {
        System.err.println("[IntellijBootstrap] Initializing IntelliJ platform via test framework...");

        // Set JNA boot library path if not already set
        if (System.getProperty("jna.boot.library.path") == null) {
            String homePath = com.intellij.openapi.application.PathManager.getHomePath();
            String arch = System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm64")
                ? "aarch64" : "amd64";
            String jnaPath = homePath + "/lib/jna/" + arch;
            if (new java.io.File(jnaPath).isDirectory()) {
                System.setProperty("jna.boot.library.path", jnaPath);
            }
        }

        // TestApplicationManager.getInstance() does the full bootstrap:
        // - Sets AppMode headless flags
        // - Starts Fleet kernel
        // - Loads plugins
        // - Creates ApplicationImpl
        // - Registers components
        // - Initializes config store, registry, services
        // - Calls AppInitializedListener callbacks
        TestApplicationManager.getInstance();

        System.err.println("[IntellijBootstrap] IntelliJ platform initialized successfully");
    }
}
