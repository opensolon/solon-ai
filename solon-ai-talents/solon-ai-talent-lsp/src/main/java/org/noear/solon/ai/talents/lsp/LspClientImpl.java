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
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.noear.solon.ai.talents.lsp.exception.LspCommandNotFoundException;
import org.noear.solon.ai.talents.lsp.exception.LspEnvironmentException;
import org.noear.solon.ai.talents.lsp.exception.LspStartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    /**
     * 诊断推送的去抖窗口：命中新鲜诊断后再静默等待这么久，收拢服务器分多次推送的场景
     */
    private static final long DIAGNOSTICS_DEBOUNCE_MS = Long.getLong("lsp.diagnosticsDebounce", 150L);

    private LanguageServer remoteServer;
    private Process process;
    private final String rootUri;
    private final String rootDir;

    /**
     * 已打开文件的缓存：uri -> 文档状态（版本号 + 最近同步的文本）
     */
    private final ConcurrentHashMap<String, DocState> openedFiles = new ConcurrentHashMap<>();

    /**
     * 诊断状态：uri -> 最近一次推送与最近一次同步的对齐信息
     */
    private final ConcurrentHashMap<String, DiagState> diagStates = new ConcurrentHashMap<>();

    /** 按 uri 串行化 syncFile，避免并发算出同一份增量范围而被服务器重复应用 */
    private final ConcurrentHashMap<String, Object> syncLocks = new ConcurrentHashMap<>();

    /**
     * 诊断等待的唤醒锁（publishDiagnostics 到达时 notifyAll）
     */
    private final Object diagMonitor = new Object();

    /**
     * 服务器声明的文档同步方式（决定 didChange 用整文替换还是全范围替换）
     */
    private volatile TextDocumentSyncKind syncKind = TextDocumentSyncKind.Full;

    /**
     * 诊断信息回调：uri -> 结构化诊断列表
     */
    private BiConsumer<String, List<Diagnostic>> diagnosticsConsumer;

    private static class DocState {
        final int version;
        final String text;

        DocState(int version, String text) {
            this.version = version;
            this.text = text;
        }
    }

    private static class DiagState {
        volatile List<Diagnostic> items = Collections.emptyList();
        /** 最近一次 publishDiagnostics 到达时间 */
        volatile long publishedAt;
        /** 最近一次 publishDiagnostics 携带的文档版本（多数服务器不带，为 null） */
        volatile Integer publishedVersion;
        /** 最近一次 syncFile 的起始时间 */
        volatile long syncAt;
        /** 最近一次 syncFile 之后的文档版本 */
        volatile int expectVersion;
    }

    public LspClientImpl(String serverName, String[] command, String rootDir) throws Exception {
        this(serverName, command, rootDir, null, null);
    }

    /**
     * 测试用构造：不启动子进程，直接注入远端服务。
     *
     * <p>生产代码请使用带 command 的构造函数。
     */
    LspClientImpl(String rootDir, LanguageServer remoteServer) {
        this.rootDir = rootDir;
        this.rootUri = new File(rootDir).toURI().toString();
        this.remoteServer = remoteServer;
        this.process = null;
    }

    public LspClientImpl(String serverName, String[] command, String rootDir,
                         Map<String, Object> initializationOptions,
                         Map<String, String> env) throws Exception {
        this.rootDir = rootDir;
        this.rootUri = new File(rootDir).toURI().toString();

        // 1. 启动语言服务器进程（对齐 OpenCode：必须继承父进程环境变量）
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(rootDir));
        // 合并父进程环境变量 + 用户自定义环境变量
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }

        try {
            this.process = builder.start();
        } catch (java.io.IOException e) {
            // 进程完全启动不了 -> 命令不存在
            throw new LspCommandNotFoundException(serverName, command, e);
        }

        // 启动后立即检查：如果进程瞬间退出，说明命令存在但环境有问题
        try {
            Thread.sleep(100); // 短暂等待，让可能的启动错误暴露出来
        } catch (InterruptedException ignored) {
        }

        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            String stderr = readStderr();
            throw new LspEnvironmentException(serverName, command,
                    "Process exited immediately with code " + exitCode
                            + (stderr.isEmpty() ? "" : ". Error output: " + truncate(stderr, 500)),
                    null
            );
        }

        InputStream in = process.getInputStream();
        OutputStream out = process.getOutputStream();

        // 2. 建立 JSON-RPC 连接
        Launcher<LanguageServer> launcher = Launcher.createLauncher(
                this, LanguageServer.class, in, out, Executors.newCachedThreadPool(), (consume) -> consume
        );

        launcher.startListening();
        this.remoteServer = launcher.getRemoteProxy();

        // 3. 协议握手流程 (必须执行，对齐 OpenCode 的初始化模式)
        InitializeParams initParams = new InitializeParams();
        initParams.setRootUri(this.rootUri);
        initParams.setRootPath(this.rootDir);
        initParams.setCapabilities(new ClientCapabilities());
        // 传递 initializationOptions（如 Python 的 pythonPath、PHP 的 telemetry 等）
        if (initializationOptions != null && !initializationOptions.isEmpty()) {
            initParams.setInitializationOptions(initializationOptions);
        }

        // 带超时的 initialize，防止子进程启动失败时无限阻塞
        long initTimeout = Long.parseLong(System.getProperty("lsp.initTimeout", "30"));
        try {
            InitializeResult initResult = remoteServer.initialize(initParams).get(initTimeout, TimeUnit.SECONDS);
            resolveSyncKind(initResult);
        } catch (java.util.concurrent.TimeoutException e) {
            // 超时时检查进程是否已经死亡
            if (!process.isAlive()) {
                // 进程在握手期间死亡 -> 环境不满足（如 Java 版本过低）
                int exitCode = process.exitValue();
                String stderr = readStderr();
                throw new LspEnvironmentException(serverName, command,
                        "Process died during initialization (exit code " + exitCode + ")"
                                + (stderr.isEmpty() ? "" : ". Error output: " + truncate(stderr, 500)),
                        e
                );
            }
            // 进程还活着但超时 -> 初始化超时（可能是项目太大或服务器响应慢）
            throw new LspStartException(serverName, command,
                    new RuntimeException("LSP initialize timed out after " + initTimeout + "s"));
        } catch (Exception e) {
            // 握手期间的其他异常（如 JSON-RPC 解析错误）
            if (!process.isAlive()) {
                String stderr = readStderr();
                throw new LspEnvironmentException(serverName, command,
                        "Initialization failed, process exited"
                                + (stderr.isEmpty() ? "" : ". Error output: " + truncate(stderr, 500)),
                        e
                );
            }
            throw e;
        }
        remoteServer.initialized(new InitializedParams());
    }

    /**
     * 同步文件内容给语言服务器。
     *
     * <p>首次调用发 didOpen；之后若磁盘内容已变化（或 forceChange=true），发
     * didChangeWatchedFiles + didChange 并递增版本号——否则服务器会一直持有旧文本，
     * 写文件后拿到的诊断永远是过期的。
     */
    @Override
    public int syncFile(String uri, boolean forceChange) {
        // 「读取内容 → 比较 → 计算增量范围 → 发送 → 更新记录」必须整体原子：
        // 两个线程并发进入会基于同一份旧文本算出相同的 range，服务器按序应用两次即造成文本错位
        // （表现为方法重复、游离的 '}' 与超出文件行数的诊断）。
        synchronized (syncLocks.computeIfAbsent(uri, k -> new Object())) {
            return syncFileInLock(uri, forceChange);
        }
    }

    private int syncFileInLock(String uri, boolean forceChange) {
        String content = readFileContent(uri);

        DiagState ds = diagStates.computeIfAbsent(uri, k -> new DiagState());
        DocState doc = openedFiles.get(uri);

        int version;
        if (doc == null) {
            version = 1;
            ds.syncAt = System.currentTimeMillis();
            ds.expectVersion = version;

            String languageId = detectLanguageId(uri);
            remoteServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, languageId, version, content)
            ));
            openedFiles.put(uri, new DocState(version, content));
            return version;
        }

        if (forceChange == false && content.equals(doc.text)) {
            // 内容没变：不重复通知，只对齐等待基线（服务器已有的诊断仍然有效）
            ds.syncAt = System.currentTimeMillis();
            ds.expectVersion = doc.version;
            return doc.version;
        }

        version = doc.version + 1;
        ds.syncAt = System.currentTimeMillis();
        ds.expectVersion = version;

        // 这里刻意不发 didChangeWatchedFiles：文档已 didOpen，其内容真相归客户端所有，
        // 变更只应通过 didChange 表达。jdtls 之类的服务器收到 watched-files 事件会自行重读磁盘，
        // 与随后的 didChange 形成同一变更的双重应用，进而把文档文本撕裂。

        // 注意：这里不清空已有诊断。部分服务器（如 clangd）只在内容真正变化时才重新推送，
        // 提前清空会让无变化的 touch 把已有错误抹掉。
        TextDocumentContentChangeEvent change;
        if (syncKind == TextDocumentSyncKind.Incremental) {
            // 声明增量同步的服务器不接受裸整文替换，需用覆盖全文的 range。
            // 终点取「旧文本」与「新内容」中更靠后的那个：若服务器端文本因外部原因已领先于
            // 客户端记录，仍能整体覆盖；越界位置按 LSP 规范由服务器 clamp 到文档末尾。
            change = new TextDocumentContentChangeEvent(
                    new Range(new Position(0, 0), maxEndPosition(doc.text, content)), content);
        } else {
            change = new TextDocumentContentChangeEvent(content);
        }

        remoteServer.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                Arrays.asList(change)
        ));
        openedFiles.put(uri, new DocState(version, content));
        return version;
    }

    /**
     * 等待最近一次 syncFile 之后的诊断推送。
     *
     * <p>仅支持 push 通道（textDocument/publishDiagnostics）：命中新鲜推送后再做一次
     * 去抖静默等待，避免拿到服务器分多次推送中的第一批（常见为空列表）。
     */
    @Override
    public List<Diagnostic> waitForDiagnostics(String uri, long timeoutMs) {
        DiagState ds = diagStates.get(uri);
        if (ds == null) {
            return Collections.emptyList();
        }

        long startAt = ds.syncAt;
        int expectVersion = ds.expectVersion;
        long deadline = startAt + Math.max(0, timeoutMs);

        synchronized (diagMonitor) {
            while (true) {
                long now = System.currentTimeMillis();
                if (isFreshPublish(ds, startAt, expectVersion)) {
                    long quiet = DIAGNOSTICS_DEBOUNCE_MS - (now - ds.publishedAt);
                    if (quiet <= 0 || now >= deadline) {
                        return ds.items;
                    }
                    doWait(Math.min(quiet, deadline - now));
                    continue;
                }

                long left = deadline - now;
                if (left <= 0) {
                    return ds.items;
                }
                doWait(left);
            }
        }
    }

    @Override
    public List<Diagnostic> getDiagnostics(String uri) {
        DiagState ds = diagStates.get(uri);
        return (ds == null) ? Collections.emptyList() : ds.items;
    }

    private boolean isFreshPublish(DiagState ds, long startAt, int expectVersion) {
        if (ds.publishedAt == 0L) {
            return false;
        }
        Integer pv = ds.publishedVersion;
        if (pv != null) {
            // 服务器带了版本号：只认对应版本的推送
            return pv == expectVersion;
        }
        return ds.publishedAt >= startAt;
    }

    private void doWait(long ms) {
        try {
            diagMonitor.wait(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String readFileContent(String uri) {
        try {
            Path filePath = Paths.get(java.net.URI.create(uri));
            if (Files.exists(filePath)) {
                return new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOG.debug("Failed to read file content for {}: {}", uri, e.getMessage());
        }
        return "";
    }

    /**
     * 取两段文本末尾位置中更靠后的那个，用于构造「一定能覆盖全文」的替换范围。
     */
    private static Position maxEndPosition(String a, String b) {
        Position pa = endPosition(a);
        Position pb = endPosition(b);
        if (pa.getLine() != pb.getLine()) {
            return pa.getLine() > pb.getLine() ? pa : pb;
        }
        return pa.getCharacter() >= pb.getCharacter() ? pa : pb;
    }

    private static Position endPosition(String text) {
        int line = 0;
        int lastLineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lastLineStart = i + 1;
            }
        }
        return new Position(line, text.length() - lastLineStart);
    }

    private void resolveSyncKind(InitializeResult initResult) {
        try {
            if (initResult == null || initResult.getCapabilities() == null) {
                return;
            }
            Either<TextDocumentSyncKind, TextDocumentSyncOptions> sync = initResult.getCapabilities().getTextDocumentSync();
            if (sync == null) {
                return;
            }
            if (sync.isLeft() && sync.getLeft() != null) {
                syncKind = sync.getLeft();
            } else if (sync.isRight() && sync.getRight() != null && sync.getRight().getChange() != null) {
                syncKind = sync.getRight().getChange();
            }
        } catch (Exception e) {
            LOG.debug("Failed to resolve textDocumentSync kind: {}", e.getMessage());
        }
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
    public void telemetryEvent(Object object) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        String uri = diagnostics.getUri();
        if (uri == null) {
            return;
        }

        List<Diagnostic> raw = diagnostics.getDiagnostics();
        List<Diagnostic> items = (raw == null || raw.isEmpty())
                ? Collections.<Diagnostic>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(raw));

        DiagState ds = diagStates.computeIfAbsent(uri, k -> new DiagState());
        ds.items = items;
        ds.publishedVersion = diagnostics.getVersion();
        ds.publishedAt = System.currentTimeMillis();

        // 唤醒 waitForDiagnostics 的等待者（状态均为 volatile 写，持锁区间只做通知）
        synchronized (diagMonitor) {
            diagMonitor.notifyAll();
        }

        if (diagnosticsConsumer != null) {
            diagnosticsConsumer.accept(uri, items);
        }
    }

    @Override
    public void showMessage(MessageParams messageParams) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        LOG.debug("LSP server log: [{}] {}", message.getType(), message.getMessage());
    }

    /**
     * 设置诊断信息回调（结构化诊断列表，渲染由上层决定）
     */
    public void setDiagnosticsConsumer(BiConsumer<String, List<Diagnostic>> consumer) {
        this.diagnosticsConsumer = consumer;
    }

    /**
     * 优雅关闭（对齐 LSP 规范：shutdown -> exit -> destroy）
     */
    @Override
    public void shutdown() {
        try {
            if (remoteServer != null) {
                remoteServer.shutdown().get(5, TimeUnit.SECONDS);
                remoteServer.exit();
            }
        } catch (Exception e) {
            LOG.debug("LSP graceful shutdown failed, force destroying: {}", e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
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
        if (lower.endsWith(".mts")) return "typescript";
        if (lower.endsWith(".cts")) return "typescript";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".jsx")) return "javascriptreact";
        if (lower.endsWith(".mjs")) return "javascript";
        if (lower.endsWith(".cjs")) return "javascript";
        if (lower.endsWith(".py") || lower.endsWith(".pyi")) return "python";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx") || lower.endsWith(".c++") || lower.endsWith(".hpp") || lower.endsWith(".h++") || lower.endsWith(".hh") || lower.endsWith(".hxx"))
            return "cpp";
        if (lower.endsWith(".cs") || lower.endsWith(".csx")) return "csharp";
        if (lower.endsWith(".rb") || lower.endsWith(".rake") || lower.endsWith(".gemspec")) return "ruby";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".dart")) return "dart";
        if (lower.endsWith(".lua")) return "lua";
        if (lower.endsWith(".swift")) return "swift";
        if (lower.endsWith(".scala")) return "scala";
        if (lower.endsWith(".xml") || lower.endsWith(".xhtml") || lower.endsWith(".fxml")) return "xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") || lower.endsWith(".ksh"))
            return "shellscript";
        return "plaintext";
    }

    private static String severityToString(DiagnosticSeverity severity) {
        switch (severity) {
            case Error:
                return "ERROR";
            case Warning:
                return "WARN";
            case Information:
                return "INFO";
            case Hint:
                return "HINT";
            default:
                return "?";
        }
    }

    /**
     * 读取进程的 stderr 输出（用于错误诊断）
     */
    private String readStderr() {
        if (process == null) return "";
        try {
            InputStream errStream = process.getErrorStream();
            if (errStream.available() > 0) {
                byte[] bytes = new byte[Math.min(errStream.available(), 4096)];
                int read = errStream.read(bytes);
                if (read > 0) {
                    return new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 截断字符串，避免异常信息过长
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}