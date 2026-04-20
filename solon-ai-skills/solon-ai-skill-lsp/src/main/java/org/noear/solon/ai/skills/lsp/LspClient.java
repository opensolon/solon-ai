package org.noear.solon.ai.skills.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LSP 客户端接口
 */
public interface LspClient extends LanguageClient {
    void touchFile(String uri) throws Exception;

    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params);

    CompletableFuture<List<? extends Location>> references(ReferenceParams params);

    CompletableFuture<Hover> hover(HoverParams params);

    CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params);

    CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> workspaceSymbol(WorkspaceSymbolParams params);

    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params);

    CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params);

    CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(String uri, int line, int offset);

    CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(String uri, int line, int offset);

    void shutdown();
}