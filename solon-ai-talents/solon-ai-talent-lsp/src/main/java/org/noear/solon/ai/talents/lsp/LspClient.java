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
     * {@link #syncFile} 的返回值：本次未能同步（未拿到同步锁、或发送被判定卡死）。
     *
     * <p>调用方拿到它就不该再等待诊断：服务器手里的文本与磁盘不一致，任何结论都不可信。
     */
    int VERSION_UNSYNCED = -1;

    /**
     * 同步文件内容给语言服务器（首次 didOpen，之后按内容变化 didChange）
     *
     * @param uri         文件 uri
     * @param forceChange 内容未变化时是否也强制发一次 didChange
     * @return 同步后的文档版本号；{@link #VERSION_UNSYNCED} 表示本次未同步
     */
    int syncFile(String uri, boolean forceChange) throws Exception;

    /**
     * 客户端是否仍可用。
     *
     * <p>{@code false} 表示这个连接已经废掉（进程退出、或对端停止读取 stdin 后被强制回收），
     * 上层应当丢弃并重建，而不是继续往里发消息。
     */
    default boolean isAlive() {
        return true;
    }

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
        return waitForDiagnosticsResult(uri, timeoutMs).getItems();
    }

    /**
     * 同 {@link #waitForDiagnostics}，但额外给出「本轮结论是否已确认」。
     *
     * <p>调用方要区分「确认无错」与「等待超时、结果未知」时必须用这个方法：空列表本身
     * 无法表达两者的差别。默认实现按未确认返回，避免未覆盖该语义的实现给出过强保证。
     */
    default LspDiagnosticsResult waitForDiagnosticsResult(String uri, long timeoutMs) {
        return LspDiagnosticsResult.unconfirmed(getDiagnostics(uri));
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
