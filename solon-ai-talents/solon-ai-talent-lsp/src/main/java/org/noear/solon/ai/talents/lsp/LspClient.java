package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LSP 客户端接口
 */
public interface LspClient extends LanguageClient {
    /**
     * 同步文件内容给语言服务器（首次 didOpen，之后按内容变化 didChange）
     *
     * @param uri         文件 uri
     * @param forceChange 内容未变化时是否也强制发一次 didChange
     * @return 同步后的文档版本号
     */
    int syncFile(String uri, boolean forceChange) throws Exception;

    /**
     * 同步文件内容给语言服务器（内容未变化时不重复通知）
     */
    default void touchFile(String uri) throws Exception {
        syncFile(uri, false);
    }

    /**
     * 等待最近一次 {@link #syncFile} 之后的诊断推送（超时则返回当前已有诊断）
     *
     * @param uri       文件 uri
     * @param timeoutMs 最长等待毫秒数
     */
    default List<Diagnostic> waitForDiagnostics(String uri, long timeoutMs) {
        return getDiagnostics(uri);
    }

    /**
     * 获取当前已缓存的诊断（不等待）
     */
    default List<Diagnostic> getDiagnostics(String uri) {
        return Collections.emptyList();
    }

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
