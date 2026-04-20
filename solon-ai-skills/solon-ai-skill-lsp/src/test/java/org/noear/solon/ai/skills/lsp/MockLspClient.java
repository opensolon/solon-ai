package org.noear.solon.ai.skills.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MockLspClient implements LspClient {
    @Override public void touchFile(String uri) {}
    @Override public void shutdown() {}

    // LanguageClient 抽象方法
    @Override public void telemetryEvent(Object object) {}
    @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
    @Override public void showMessage(MessageParams messageParams) {}
    @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }
    @Override public void logMessage(MessageParams message) {}

    // LSP 操作方法
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.completedFuture(Either.forLeft(new ArrayList<>()));
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.completedFuture(new Hover());
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> workspaceSymbol(WorkspaceSymbolParams params) {
        return CompletableFuture.completedFuture(Either.forRight(new ArrayList<>()));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        return CompletableFuture.completedFuture(Either.forLeft(new ArrayList<>()));
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(String uri, int line, int offset) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(String uri, int line, int offset) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }
}