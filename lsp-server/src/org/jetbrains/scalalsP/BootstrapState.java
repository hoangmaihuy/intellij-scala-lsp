package org.jetbrains.scalalsP;

import java.util.concurrent.CountDownLatch;

/** Shared bootstrap state accessible from ScalaLspMain (stdio), DaemonServer, and tests. */
public final class BootstrapState {
    public static final CountDownLatch bootstrapComplete = new CountDownLatch(1);
    private BootstrapState() {}
}
