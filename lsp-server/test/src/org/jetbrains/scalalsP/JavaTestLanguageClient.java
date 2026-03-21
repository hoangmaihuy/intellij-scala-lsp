package org.jetbrains.scalalsP;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Java implementation of LanguageClient for tests.
 * Avoids Scala 3 bridge method issues with lsp4j's ServiceEndpoints scanning.
 */
public class JavaTestLanguageClient implements LanguageClient {
    @Override public void telemetryEvent(Object object) {}
    @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
    @Override public void showMessage(MessageParams messageParams) {}
    @Override public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    @Override public void logMessage(MessageParams message) {}
    @Override public java.util.concurrent.CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    @Override public void notifyProgress(ProgressParams params) {}
}
