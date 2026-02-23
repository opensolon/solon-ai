package org.noear.solon.ai.skills.cli;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * LspClient 修正版实现：对齐 LSP4J 标准接口与逻辑
 */
public class LspClientImpl implements LspTool.LspClient, LanguageClient {

    private LanguageServer remoteServer;
    private Process process;
    private final String rootUri;

    public LspClientImpl(String[] command, String rootDir) throws Exception {
        this.rootUri = new java.io.File(rootDir).toURI().toString();

        // 1. 启动语言服务器进程
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new java.io.File(rootDir));
        this.process = builder.start();

        InputStream in = process.getInputStream();
        OutputStream out = process.getOutputStream();

        // 2. 建立 JSON-RPC 连接 (注意：LSPLauncher.createClientLauncher 是更简单的封装)
        Launcher<LanguageServer> launcher = Launcher.createLauncher(
                this, LanguageServer.class, in, out, Executors.newCachedThreadPool(), (consume) -> consume
        );

        launcher.startListening();
        this.remoteServer = launcher.getRemoteProxy();

        // 3. 协议握手流程 (必须执行)
        InitializeParams initParams = new InitializeParams();
        initParams.setRootUri(this.rootUri);
        initParams.setCapabilities(new ClientCapabilities());
        remoteServer.initialize(initParams).get();
        remoteServer.initialized(new InitializedParams());
    }

    @Override
    public void touchFile(String uri) {
        // 通知服务器文件状态，version 传 1 即可
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "plaintext", 1, "")
        );
        remoteServer.getTextDocumentService().didOpen(params);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return remoteServer.getTextDocumentService().definition(params);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return remoteServer.getTextDocumentService().references(params);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return remoteServer.getTextDocumentService().hover(params);
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return remoteServer.getTextDocumentService().documentSymbol(params);
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> workspaceSymbol(WorkspaceSymbolParams params) {
       return remoteServer.getWorkspaceService().symbol(params);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        return remoteServer.getTextDocumentService().implementation(params);
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        return remoteServer.getTextDocumentService().prepareCallHierarchy(params);
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(String uri, int line, int offset) {
        return remoteServer.getTextDocumentService()
                .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(line, offset)))
                .thenCompose(items -> {
                    if (items == null || items.isEmpty()) return CompletableFuture.completedFuture(null);
                    // 修正：使用正确的 LSP4J 方法名与包装参数
                    return remoteServer.getTextDocumentService().callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.get(0)));
                });
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(String uri, int line, int offset) {
        return remoteServer.getTextDocumentService()
                .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(line, offset)))
                .thenCompose(items -> {
                    if (items == null || items.isEmpty()) return CompletableFuture.completedFuture(null);
                    // 修正：使用正确的 LSP4J 方法名与包装参数
                    return remoteServer.getTextDocumentService().callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.get(0)));
                });
    }

    // --- LanguageClient 接口实现 ---

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}

    @Override
    public void showMessage(MessageParams messageParams) {}

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        // 服务器日志输出点
    }

    public void shutdown() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}