/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * LspClient 修正版实现：对齐 LSP4J 标准接口与逻辑
 * <p>
 * <ul>
 *   <li>文件内容同步（didOpen/didChange）</li>
 *   <li>诊断信息收集与格式化</li>
 *   <li>优雅关闭流程（shutdown -> exit）</li>
 * </ul>
 */
public class LspClientImpl implements LspClient {
    private static final Logger LOG = LoggerFactory.getLogger(LspClientImpl.class);

    private LanguageServer remoteServer;
    private Process process;
    private final String rootUri;
    private final String rootDir;

    /**
     * 已打开文件的缓存：uri -> 版本号
     */
    private final ConcurrentHashMap<String, Integer> openedFiles = new ConcurrentHashMap<>();

    /**
     * 诊断信息回调：uri -> diagnostics 文本
     */
    private BiConsumer<String, String> diagnosticsConsumer;

    public LspClientImpl(String[] command, String rootDir) throws Exception {
        this.rootDir = rootDir;
        this.rootUri = new java.io.File(rootDir).toURI().toString();

        // 1. 启动语言服务器进程
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new java.io.File(rootDir));
        this.process = builder.start();

        InputStream in = process.getInputStream();
        OutputStream out = process.getOutputStream();

        // 2. 建立 JSON-RPC 连接
        Launcher<LanguageServer> launcher = Launcher.createLauncher(
                this, LanguageServer.class, in, out, java.util.concurrent.Executors.newCachedThreadPool(), (consume) -> consume
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
        // 如果文件已经打开过，不再重复打开
        if (openedFiles.containsKey(uri)) {
            return;
        }

        // 尝试读取文件真实内容（对齐 OpenCode 的文件同步逻辑）
        String content = "";
        try {
            Path filePath = Paths.get(java.net.URI.create(uri));
            if (Files.exists(filePath)) {
                byte[] bytes = Files.readAllBytes(filePath);
                content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // 读取失败时使用空内容
            LOG.debug("Failed to read file content for {}: {}", uri, e.getMessage());
        }

        // 通知服务器文件状态
        String languageId = detectLanguageId(uri);
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, languageId, 1, content)
        );
        remoteServer.getTextDocumentService().didOpen(params);
        openedFiles.put(uri, 1);
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
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        if (diagnosticsConsumer != null) {
            String uri = diagnostics.getUri();
            List<Diagnostic> items = diagnostics.getDiagnostics();

            if (items == null || items.isEmpty()) {
                diagnosticsConsumer.accept(uri, null);
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (Diagnostic d : items) {
                Position start = d.getRange().getStart();
                sb.append(String.format("%s:%d:%d [%s] %s",
                        uri, start.getLine() + 1, start.getCharacter() + 1,
                        d.getSeverity() != null ? severityToString(d.getSeverity()) : "?",
                        d.getMessage()));
                if (d.getSource() != null) {
                    sb.append(" (").append(d.getSource()).append(")");
                }
                sb.append("\n");
            }
            diagnosticsConsumer.accept(uri, sb.toString().trim());
        }
    }

    @Override
    public void showMessage(MessageParams messageParams) {}

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        LOG.debug("LSP server log: [{}] {}", message.getType(), message.getMessage());
    }

    /**
     * 设置诊断信息回调
     */
    public void setDiagnosticsConsumer(BiConsumer<String, String> consumer) {
        this.diagnosticsConsumer = consumer;
    }

    /**
     * 优雅关闭（对齐 LSP 规范：shutdown -> exit -> destroy）
     */
    @Override
    public void shutdown() {
        try {
            if (remoteServer != null) {
                remoteServer.shutdown().get();
                remoteServer.exit();
            }
        } catch (Exception e) {
            LOG.debug("LSP graceful shutdown failed, force destroying: {}", e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    // ---- 内部辅助 ----

    private static String detectLanguageId(String uri) {
        if (uri == null) return "plaintext";
        String lower = uri.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".tsx")) return "typescriptreact";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".jsx")) return "javascriptreact";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx") || lower.endsWith(".hpp")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".scala")) return "scala";
        if (lower.endsWith(".xml") || lower.endsWith(".xhtml") || lower.endsWith(".fxml")) return "xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh")) return "shellscript";
        return "plaintext";
    }

    private static String severityToString(DiagnosticSeverity severity) {
        switch (severity) {
            case Error: return "ERROR";
            case Warning: return "WARN";
            case Information: return "INFO";
            case Hint: return "HINT";
            default: return "?";
        }
    }
}