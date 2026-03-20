package org.jetbrains.scalalsP;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Creates the lsp4j Launcher with a workaround for Scala 3 bridge method issues.
 * <p>
 * Scala 3 generates bridge methods for inherited default interface methods that copy
 * {@code @JsonNotification}/{@code @JsonRequest} annotations, causing lsp4j to find
 * duplicate RPC method names when scanning Scala classes via reflection.
 * <p>
 * Workaround: wrap the server and its delegate services in Java classes (which don't
 * have bridge method annotation issues), then build the Launcher from the Java wrappers.
 */
public final class LspLauncher {

    private LspLauncher() {}

    /**
     * Creates an LSP JSON-RPC connection, connects the client, and blocks until the connection closes.
     */
    public static void startAndAwait(ScalaLspServer server, InputStream in, OutputStream out) throws Exception {
        // Wrap server in a Java class to avoid Scala 3 bridge method annotation duplication
        var javaServer = new JavaLanguageServer(server);

        // Pre-compute supported methods from Java interfaces only
        Map<String, JsonRpcMethod> serverMethods = ServiceEndpoints.getSupportedMethods(LanguageServer.class);
        Map<String, JsonRpcMethod> clientMethods = ServiceEndpoints.getSupportedMethods(LanguageClient.class);
        Map<String, JsonRpcMethod> allMethods = new LinkedHashMap<>();
        allMethods.putAll(serverMethods);
        allMethods.putAll(clientMethods);

        // Create endpoint from the Java wrapper (no bridge methods)
        Endpoint localEndpoint = ServiceEndpoints.toEndpoint(javaServer);

        Launcher<LanguageClient> launcher = new Launcher.Builder<LanguageClient>() {
            @Override
            protected Map<String, JsonRpcMethod> getSupportedMethods() {
                return allMethods;
            }

            @Override
            protected RemoteEndpoint createRemoteEndpoint(MessageJsonHandler jsonHandler) {
                var outConsumer = new StreamMessageConsumer(out, jsonHandler);
                var wrappedConsumer = wrapMessageConsumer(outConsumer);
                return new RemoteEndpoint(wrappedConsumer, localEndpoint);
            }
        }
            .setLocalService(javaServer)
            .setRemoteInterface(LanguageClient.class)
            .setInput(in)
            .setOutput(out)
            .create();

        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        System.err.println("[ScalaLsp] LSP server started, listening on stdin/stdout");
        System.err.println("[ScalaLsp] Waiting for LSP messages on stdin...");
        launcher.startListening().get();
        System.err.println("[ScalaLsp] LSP listener stopped");
    }

    /**
     * Java wrapper around LanguageServer that delegates all calls.
     * This avoids Scala 3 bridge methods which copy @JsonNotification annotations.
     */
    private static class JavaLanguageServer implements LanguageServer {
        private final ScalaLspServer delegate;

        JavaLanguageServer(ScalaLspServer delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return delegate.initialize(params);
        }

        @Override
        public void initialized(InitializedParams params) {
            delegate.initialized(params);
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return delegate.shutdown();
        }

        @Override
        public void exit() {
            delegate.exit();
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            // Return a Java wrapper around the Scala TextDocumentService
            return new JavaTextDocumentService(delegate.getTextDocumentService());
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            // Return a Java wrapper around the Scala WorkspaceService
            return new JavaWorkspaceService(delegate.getWorkspaceService());
        }
    }

    /**
     * Java wrapper around TextDocumentService. Delegates all calls.
     * Only needs to wrap methods that are actually implemented.
     */
    private static class JavaTextDocumentService implements TextDocumentService {
        private final TextDocumentService delegate;

        JavaTextDocumentService(TextDocumentService delegate) {
            this.delegate = delegate;
        }

        @Override public CompletableFuture<Either<java.util.List<CompletionItem>, CompletionList>> completion(CompletionParams params) { return delegate.completion(params); }
        @Override public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem params) { return delegate.resolveCompletionItem(params); }
        @Override public CompletableFuture<Hover> hover(HoverParams params) { return delegate.hover(params); }
        @Override public CompletableFuture<Either<java.util.List<? extends Location>, java.util.List<? extends LocationLink>>> definition(DefinitionParams params) { return delegate.definition(params); }
        @Override public CompletableFuture<Either<java.util.List<? extends Location>, java.util.List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams params) { return delegate.typeDefinition(params); }
        @Override public CompletableFuture<Either<java.util.List<? extends Location>, java.util.List<? extends LocationLink>>> implementation(ImplementationParams params) { return delegate.implementation(params); }
        @Override public CompletableFuture<java.util.List<? extends Location>> references(ReferenceParams params) { return delegate.references(params); }
        @Override public CompletableFuture<java.util.List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) { return delegate.documentHighlight(params); }
        @Override public CompletableFuture<java.util.List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) { return delegate.documentSymbol(params); }
        @Override public CompletableFuture<java.util.List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) { return delegate.codeAction(params); }
        @Override public CompletableFuture<java.util.List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) { return delegate.foldingRange(params); }
        @Override public CompletableFuture<java.util.List<SelectionRange>> selectionRange(SelectionRangeParams params) { return delegate.selectionRange(params); }
        @Override public CompletableFuture<java.util.List<InlayHint>> inlayHint(InlayHintParams params) { return delegate.inlayHint(params); }
        @Override public CompletableFuture<InlayHint> resolveInlayHint(InlayHint params) { return delegate.resolveInlayHint(params); }
        @Override public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(PrepareRenameParams params) { return delegate.prepareRename(params); }
        @Override public CompletableFuture<WorkspaceEdit> rename(RenameParams params) { return delegate.rename(params); }
        @Override public CompletableFuture<java.util.List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) { return delegate.prepareCallHierarchy(params); }
        @Override public CompletableFuture<java.util.List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) { return delegate.callHierarchyIncomingCalls(params); }
        @Override public CompletableFuture<java.util.List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) { return delegate.callHierarchyOutgoingCalls(params); }
        @Override public CompletableFuture<java.util.List<TypeHierarchyItem>> prepareTypeHierarchy(TypeHierarchyPrepareParams params) { return delegate.prepareTypeHierarchy(params); }
        @Override public CompletableFuture<java.util.List<TypeHierarchyItem>> typeHierarchySupertypes(TypeHierarchySupertypesParams params) { return delegate.typeHierarchySupertypes(params); }
        @Override public CompletableFuture<java.util.List<TypeHierarchyItem>> typeHierarchySubtypes(TypeHierarchySubtypesParams params) { return delegate.typeHierarchySubtypes(params); }
        @Override public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) { return delegate.signatureHelp(params); }
        @Override public CompletableFuture<java.util.List<? extends TextEdit>> formatting(DocumentFormattingParams params) { return delegate.formatting(params); }
        @Override public CompletableFuture<java.util.List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) { return delegate.rangeFormatting(params); }
        @Override public CompletableFuture<java.util.List<DocumentLink>> documentLink(DocumentLinkParams params) { return delegate.documentLink(params); }
        @Override public void didOpen(DidOpenTextDocumentParams params) { delegate.didOpen(params); }
        @Override public void didChange(DidChangeTextDocumentParams params) { delegate.didChange(params); }
        @Override public void didClose(DidCloseTextDocumentParams params) { delegate.didClose(params); }
        @Override public void didSave(DidSaveTextDocumentParams params) { delegate.didSave(params); }
    }

    /**
     * Java wrapper around WorkspaceService. Delegates all calls.
     */
    private static class JavaWorkspaceService implements WorkspaceService {
        private final WorkspaceService delegate;

        JavaWorkspaceService(WorkspaceService delegate) {
            this.delegate = delegate;
        }

        @Override public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) { return delegate.executeCommand(params); }
        @Override public CompletableFuture<Either<java.util.List<? extends SymbolInformation>, java.util.List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) { return delegate.symbol(params); }
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { delegate.didChangeConfiguration(params); }
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { delegate.didChangeWatchedFiles(params); }
    }
}
