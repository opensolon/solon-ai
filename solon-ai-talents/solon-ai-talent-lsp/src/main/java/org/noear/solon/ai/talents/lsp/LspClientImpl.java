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
import org.noear.solon.ai.talents.lsp.exception.LspBusyException;
import org.noear.solon.ai.talents.lsp.exception.LspCommandNotFoundException;
import org.noear.solon.ai.talents.lsp.exception.LspEnvironmentException;
import org.noear.solon.ai.talents.lsp.exception.LspStalledException;
import org.noear.solon.ai.talents.lsp.exception.LspStartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

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

    /**
     * stderr 尾部缓冲的上限：只用于把失败原因带进异常信息，无需完整日志
     */
    private static final int STDERR_TAIL_LIMIT = 8 * 1024;

    /**
     * 进程死亡后等待 stderr 排空线程收尾的时长：读到 EOF 就结束，通常远快于此
     */
    private static final long STDERR_DRAIN_JOIN_MS = 300L;

    /**
     * 单条出站消息的「软预算」：调用方最多等这么久，超时即放弃本轮（返回未同步），但不追究服务器。
     *
     * <p>曾经这里只有一个 2s 的预算，且超时就直接判定「对端不再读 stdin」并强杀进程。这个推断
     * 对 jdtls 不成立：项目导入期它的消息读取线程会被工作区锁挡住，而 didOpen 要一次性推整份
     * 文件文本，管道缓冲（通常 64KB）一满我们就阻塞——于是一个正在正常导入项目的服务器被反复
     * 杀掉，配额（{@code lsp.maxRestarts}）用尽后整个会话的 Java 语言服务静默失效。
     *
     * <p>所以软预算只负责「主流程不被拖住」，判定卡死交给 {@link #sendHardTimeoutMs}。
     */
    private static final long SEND_SOFT_TIMEOUT_DEFAULT = 5000L;

    /**
     * 单条出站消息的「硬预算」：同一次写入卡这么久才判定对端确实停止消费 stdin。
     *
     * <p>健康服务器下写管道是微秒级操作，慢也慢在「忙」；只有长时间毫无进展才是不可自愈的
     * 双向管道死锁，那时才值得付出杀进程 + 冷启动重建的代价。
     */
    private static final long SEND_HARD_TIMEOUT_DEFAULT = 60_000L;

    /**
     * 就绪信号的兜底预算（见 {@link LspReadyGate}）
     */
    private static final long READY_TIMEOUT_DEFAULT = 180_000L;

    /**
     * 关闭握手的等待预算
     */
    private static final long SHUTDOWN_TIMEOUT_MS = Long.getLong("lsp.shutdownTimeout", 3000L);

    private final String serverName;
    private LanguageServer remoteServer;
    private Process process;
    private final String rootUri;
    private final String rootDir;

    /**
     * 语言服务器 stderr 的尾部内容（由排空线程写入）
     */
    private final StringBuilder stderrTail = new StringBuilder();

    /**
     * stderr 排空线程：必须常驻。语言服务器（尤其 jdtls/Eclipse JDT）会持续往 stderr 写日志，
     * 没人读就会在管道缓冲区写满后卡住，进而停止读自己的 stdin —— 于是我们发 didChange 的
     * write 永久阻塞，形成不可自愈的双向管道死锁。
     */
    private Thread stderrDrain;

    /**
     * 出站消息的唯一发送线程。
     *
     * <p>写管道是阻塞操作，必须与业务线程隔离：一旦对端不读，只允许这一条线程被拖住，
     * 调用方拿到超时后即可降级。lsp4j 内部本就用锁把出站写串行化，这里不引入额外排队损耗。
     */
    private final ExecutorService sender;

    /**
     * JSON-RPC 入站消息的处理线程池（daemon，随本客户端关闭）
     */
    private ExecutorService rpcExecutor;

    /**
     * 对端已停止消费 stdin（写入超过硬预算）。一经判定即永久成立：此进程已不可信，只能重建。
     */
    private volatile boolean stalled;

    /**
     * 当前正在发送的消息描述与起始时间（0 表示空闲）。
     *
     * <p>发送线程只有一条，所以「是否有一次写入卡住了、卡了多久」是全局事实：
     * 据此即可区分「服务器在忙」（本轮降级、下次再试）与「服务器死了」（杀掉重建），
     * 并让后续调用方在已知堆积时立即降级，而不是每人再白白等一个软预算。
     */
    private volatile String sendInFlightDesc;
    private volatile long sendInFlightSince;

    /**
     * 单条出站消息的软/硬预算（构造时定，便于测试与按服务器调优）
     */
    private final long sendSoftTimeoutMs;
    private final long sendHardTimeoutMs;

    /**
     * 项目模型就绪门禁：就绪前的诊断一律不采信（见 {@link LspReadyGate}）
     */
    private final LspReadyGate readyGate;

    /**
     * 已关闭标记：避免重复关闭时向已停止的执行器提交任务
     */
    private volatile boolean closed;

    /**
     * 卡死通知：由 {@link LspManager} 注册，用于驱逐本实例并按配额重启
     */
    private volatile Runnable stalledListener;

    /**
     * 已打开文件的缓存：uri -> 文档状态（版本号 + 最近同步的文本）
     */
    private final ConcurrentHashMap<String, DocState> openedFiles = new ConcurrentHashMap<>();

    /**
     * 诊断状态：uri -> 最近一次推送与最近一次同步的对齐信息
     */
    private final ConcurrentHashMap<String, DiagState> diagStates = new ConcurrentHashMap<>();

    /** 按 uri 串行化 syncFile，避免并发算出同一份增量范围而被服务器重复应用 */
    private final ConcurrentHashMap<String, ReentrantLock> syncLocks = new ConcurrentHashMap<>();

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
        this.serverName = "embedded";
        this.rootDir = rootDir;
        this.rootUri = new File(rootDir).toURI().toString();
        this.remoteServer = remoteServer;
        this.process = null;
        this.sender = newSingleThreadSender(this.serverName);
        this.sendSoftTimeoutMs = longProp("lsp.sendTimeout", SEND_SOFT_TIMEOUT_DEFAULT);
        this.sendHardTimeoutMs = longProp("lsp.stallTimeout", SEND_HARD_TIMEOUT_DEFAULT);
        this.readyGate = new LspReadyGate(this.serverName, false, 0L);
    }

    public LspClientImpl(String serverName, String[] command, String rootDir,
                         Map<String, Object> initializationOptions,
                         Map<String, String> env) throws Exception {
        this.serverName = serverName;
        this.rootDir = rootDir;
        this.rootUri = new File(rootDir).toURI().toString();
        this.sender = newSingleThreadSender(serverName);
        this.sendSoftTimeoutMs = longProp("lsp.sendTimeout", SEND_SOFT_TIMEOUT_DEFAULT);
        this.sendHardTimeoutMs = longProp("lsp.stallTimeout", SEND_HARD_TIMEOUT_DEFAULT);
        //jdtls 导入项目期会推一批「无 classpath」的假诊断，必须等它自述就绪才能采信
        this.readyGate = new LspReadyGate(serverName,
                JdtlsSupport.isJdtls(command == null || command.length == 0 ? null : command[0]),
                longProp("lsp.readyTimeout", READY_TIMEOUT_DEFAULT));

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
            sender.shutdownNow();
            throw new LspCommandNotFoundException(serverName, command, e);
        }

        // 立刻接管 stderr：一刻都不能等。语言服务器启动阶段就会往 stderr 写日志，
        // 管道缓冲区（通常 64KB）一满，它就会阻塞在写 stderr 上并停止读自己的 stdin，
        // 之后我们发任何通知都会永久卡在 native write —— 双向管道死锁，不可自愈。
        startStderrDrain();

        // 启动后立即检查：如果进程瞬间退出，说明命令存在但环境有问题
        try {
            Thread.sleep(100); // 短暂等待，让可能的启动错误暴露出来
        } catch (InterruptedException ignored) {
        }

        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            String stderr = readStderr();
            sender.shutdownNow();
            throw new LspEnvironmentException(serverName, command,
                    "Process exited immediately with code " + exitCode
                            + (stderr.isEmpty() ? "" : ". Error output: " + truncate(stderr, 500)),
                    null
            );
        }

        InputStream in = process.getInputStream();
        OutputStream out = process.getOutputStream();

        // 2. 建立 JSON-RPC 连接（入站处理线程用 daemon，避免残留线程吊住 JVM 退出）
        this.rpcExecutor = Executors.newCachedThreadPool(daemonThreadFactory("lsp-rpc-" + serverName));
        Launcher<LanguageServer> launcher = Launcher.createLauncher(
                this, LanguageServer.class, in, out, rpcExecutor, (consume) -> consume
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
            //经发送线程投递：连「写请求」这一步都不能阻塞在调用方线程上
            InitializeResult initResult = requestAsync(() -> remoteServer.initialize(initParams))
                    .get(initTimeout, TimeUnit.SECONDS);
            resolveSyncKind(initResult);
        } catch (java.util.concurrent.TimeoutException e) {
            // 超时时检查进程是否已经死亡
            if (!process.isAlive()) {
                // 进程在握手期间死亡 -> 环境不满足（如 Java 版本过低）
                int exitCode = process.exitValue();
                String stderr = readStderr();
                closeQuietly();
                throw new LspEnvironmentException(serverName, command,
                        "Process died during initialization (exit code " + exitCode + ")"
                                + (stderr.isEmpty() ? "" : ". Error output: " + truncate(stderr, 500)),
                        e
                );
            }
            // 进程还活着但超时 -> 初始化超时（可能是项目太大或服务器响应慢）
            closeQuietly();
            throw new LspStartException(serverName, command,
                    new RuntimeException("LSP initialize timed out after " + initTimeout + "s"));
        } catch (Exception e) {
            // 握手期间的其他异常（如 JSON-RPC 解析错误）
            if (!process.isAlive()) {
                String stderr = readStderr();
                closeQuietly();
                throw new LspEnvironmentException(serverName, command,
                        "Initialization failed, process exited"
                                + (stderr.isEmpty() ? "" : ". Error output: " + truncate(stderr, 500)),
                        e
                );
            }
            closeQuietly();
            throw e;
        }

        notifyServerQuietly("initialized", () -> remoteServer.initialized(new InitializedParams()));
    }

    /**
     * 读取 long 型系统属性（非法值回退默认）。
     *
     * <p>每次构造时读而不是写成 static final：属性名水平不高但效果很具体——既能现场改一个
     * -D 就生效，也让超时相关的回归测试能按用例设预算。
     */
    private static long longProp(String name, long defaultValue) {
        try {
            String value = System.getProperty(name);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static ExecutorService newSingleThreadSender(String serverName) {
        return Executors.newSingleThreadExecutor(daemonThreadFactory("lsp-send-" + serverName));
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        final AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ---- 出站通道：写管道只允许阻塞发送线程，绝不阻塞业务线程 ----

    /**
     * 投递一条通知（无响应消息），最长等待 {@link #sendSoftTimeoutMs}。
     *
     * <p>两级判定：
     * <ul>
     *   <li>软超时 → 抛 {@link LspBusyException}：本轮放弃，进程不动。语言服务器在项目导入、
     *       大文件重解析期间读 stdin 本来就会变慢，把它当故障杀掉纯属自伤。</li>
     *   <li>同一次写入积压超过 {@link #sendHardTimeoutMs} → 判定卡死（杀进程 + 通知重建）：
     *       这才是不可自愈的双向管道死锁。</li>
     * </ul>
     *
     * @throws LspBusyException    对端暂时跟不上（可重试）
     * @throws LspStalledException 对端已卡死（含本次判定与此前已判定）
     */
    private void notifyServer(String desc, Runnable action) {
        if (stalled) {
            throw new LspStalledException(serverName, desc + " skipped: server was already terminated after a write stall");
        }
        if (closed) {
            throw new LspStalledException(serverName, desc + " skipped: client is closed");
        }

        //已有写入积压：仅当「等下去也必然吃满软预算」才立即降级。短暂的在飞写入是正常排队
        //（读文件预热与写文件诊断本就会并发同步不同文件），若见到在飞就放弃，一次几十毫秒的
        //慢写入便会让并发的那次诊断凭空落空——那是拿一种假象换另一种。
        long backlog = sendBacklogMs();
        if (backlog >= sendHardTimeoutMs) {
            String inFlight = markStalledInFlight(backlog);
            throw new LspStalledException(serverName,
                    desc + " skipped: " + inFlight + " has been stuck for " + backlog + "ms");
        }
        if (backlog >= sendSoftTimeoutMs) {
            throw new LspBusyException(serverName,
                    desc + " skipped: " + sendInFlightDesc + " is still in flight (" + backlog + "ms)");
        }

        Future<?> future;
        try {
            future = sender.submit(wrapSend(desc, action));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new LspStalledException(serverName, desc + " rejected: sender is shut down");
        }

        try {
            future.get(sendSoftTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            //不取消 future：写可能已进入 native write，取消不了；留给后续调用或关闭时收敛
            long stuckFor = sendBacklogMs();
            if (stuckFor >= sendHardTimeoutMs) {
                markStalled(desc, stuckFor);
                throw new LspStalledException(serverName,
                        desc + " timed out after " + stuckFor + "ms; server stopped reading stdin");
            }
            throw new LspBusyException(serverName,
                    desc + " did not complete within " + sendSoftTimeoutMs + "ms; server is busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LspBusyException(serverName, desc + " interrupted");
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() == null) ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * 发同一条通知，但把「暂时发不出去」降级为日志：用于握手收尾与关闭这类不值得失败的场景
     */
    private void notifyServerQuietly(String desc, Runnable action) {
        try {
            notifyServer(desc, action);
        } catch (LspBusyException e) {
            //通知已排在发送线程里，迟一点发出即可；不能因此让整个客户端创建失败
            LOG.debug("LSP notification '{}' deferred for '{}': {}", desc, serverName, e.getMessage());
        }
    }

    /**
     * 包装出站动作，记录「当前是否有一条写入正在进行、从何时开始」
     */
    private Runnable wrapSend(String desc, Runnable action) {
        return () -> {
            sendInFlightDesc = desc;
            sendInFlightSince = System.currentTimeMillis();
            try {
                action.run();
            } finally {
                sendInFlightSince = 0L;
                sendInFlightDesc = null;
            }
        };
    }

    /**
     * 当前写入已积压多久（0 表示发送线程空闲）
     */
    private long sendBacklogMs() {
        long since = sendInFlightSince;
        if (since == 0L) {
            return 0L;
        }
        return Math.max(1L, System.currentTimeMillis() - since);
    }

    /**
     * 投递一个请求（有响应消息）：发送动作走发送线程，返回的仍是原始响应 future。
     *
     * <p>调用方本就要给响应设超时（响应快慢取决于服务器），因此这里不额外判定卡死；
     * 真正卡死会在下一条通知上被 {@link #notifyServer} 抓到。
     */
    private <T> CompletableFuture<T> requestAsync(Supplier<CompletableFuture<T>> action) {
        if (stalled || closed) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new LspStalledException(serverName,
                    "request skipped: client is " + (stalled ? "terminated after a write stall" : "closed")));
            return failed;
        }

        try {
            return CompletableFuture.supplyAsync(action, sender).thenCompose(f -> f);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new LspStalledException(serverName, "request rejected: sender is shut down"));
            return failed;
        }
    }

    /**
     * 判定对端卡死并强制回收：destroyForcibly 会关闭管道，使阻塞在 native write 的
     * 发送线程立刻拿到 IOException 退出 —— 这是让线程不泄漏的唯一手段。
     */
    private void markStalled(String desc, long stuckForMs) {
        if (stalled) {
            return;
        }
        stalled = true;

        String tail = readStderr();
        LOG.error("LSP server '{}' stopped consuming stdin ({} stuck for {}ms, over the {}ms budget), terminating it to recover.{}",
                serverName, (desc == null) ? "an in-flight write" : desc, stuckForMs, sendHardTimeoutMs,
                tail.isEmpty() ? "" : " stderr tail: " + truncate(tail, 1000));

        destroyProcess();

        Runnable listener = this.stalledListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Throwable e) {
                LOG.debug("LSP stalled listener failed for '{}': {}", serverName, e.getMessage());
            }
        }
    }

    /**
     * 按当前在飞写入判定卡死。
     *
     * <p>{@code sendInFlightDesc} 会被发送线程随时清空，故先取一份快照再用：否则日志与异常
     * 里会出现一个 null，把「谁卡住了」这条唯一线索丢掉。
     *
     * @return 用于错误信息的在飞写入描述
     */
    private String markStalledInFlight(long backlogMs) {
        String inFlight = sendInFlightDesc;
        if (inFlight == null) {
            inFlight = "an in-flight write";
        }
        markStalled(inFlight, backlogMs);
        return inFlight;
    }

    /**
     * 注册卡死回调（由 {@link LspManager} 用于驱逐并按配额重启）
     */
    public void setStalledListener(Runnable listener) {
        this.stalledListener = listener;
    }

    /**
     * 客户端是否仍可用：进程存活、未被判定卡死、且没有超出硬预算的写入积压
     */
    @Override
    public boolean isAlive() {
        if (stalled || closed) {
            return false;
        }
        //没人再发消息时也要能收敛：积压超过硬预算即在此处判定卡死
        long backlog = sendBacklogMs();
        if (backlog >= sendHardTimeoutMs) {
            markStalledInFlight(backlog);
            return false;
        }
        return (process == null) || process.isAlive();
    }

    // ---- 就绪门禁：项目模型建好之前不采信任何分析结果 ----

    @Override
    public boolean isReady() {
        return readyGate.isReady();
    }

    @Override
    public boolean awaitReady(long timeoutMs) {
        return readyGate.await(timeoutMs);
    }

    /**
     * jdtls 私有通知 {@code language/status}：项目导入完成时会推 {@code ServiceReady}。
     *
     * <p>lsp4j 只认 {@link org.eclipse.lsp4j.services.LanguageClient} 上声明的方法，服务器私有
     * 通知需要像这样自行登记（{@code GenericEndpoint} 会扫描本实现类上的 {@code @JsonNotification}），
     * 否则只会留下一行 “Unsupported notification method” 的日志，我们也就永远无从判断它是否就绪。
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/status")
    public void languageStatus(LanguageStatusReport report) {
        if (report == null) {
            return;
        }
        if ("ServiceReady".equalsIgnoreCase(report.type) || "Ready".equalsIgnoreCase(report.type)) {
            readyGate.markReady("language/status=" + report.type);
        }
    }

    /**
     * {@code language/status} 的载荷（jdtls 私有协议，只取判定就绪所需的字段）
     */
    public static class LanguageStatusReport {
        public String type;
        public String message;
    }

    // ---- stderr 排空 ----

    private void startStderrDrain() {
        final InputStream errStream = process.getErrorStream();
        stderrDrain = new Thread(() -> {
            char[] buf = new char[1024];
            try (Reader reader = new java.io.InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buf)) != -1) {
                    appendStderrTail(buf, read);
                }
            } catch (Exception e) {
                //进程退出/管道关闭都会走到这里，属正常收尾
                LOG.trace("LSP stderr drain for '{}' ended: {}", serverName, e.getMessage());
            }
        }, "lsp-stderr-" + serverName);
        stderrDrain.setDaemon(true);
        stderrDrain.start();
    }

    private void appendStderrTail(char[] buf, int len) {
        synchronized (stderrTail) {
            stderrTail.append(buf, 0, len);
            int overflow = stderrTail.length() - STDERR_TAIL_LIMIT;
            if (overflow > 0) {
                stderrTail.delete(0, overflow);
            }
        }
    }

    /**
     * 同步文件内容给语言服务器。
     *
     * <p>首次调用发 didOpen；之后若磁盘内容已变化（或 forceChange=true），发 didChange
     * 并递增版本号——否则服务器会一直持有旧文本，写文件后拿到的诊断永远是过期的。
     *
     * @return 同步后的文档版本号；{@link LspClient#VERSION_UNSYNCED} 表示本次未能同步
     */
    @Override
    public int syncFile(String uri, boolean forceChange) {
        // 「读取内容 → 比较 → 计算增量范围 → 发送 → 更新记录」必须整体原子：
        // 两个线程并发进入会基于同一份旧文本算出相同的 range，服务器按序应用两次即造成文本错位
        // （表现为方法重复、游离的 '}' 与超出文件行数的诊断）。
        // 用 tryLock 而非 synchronized：前一个持锁者若正卡在写管道上，后来者宁可放弃本次
        // 诊断，也不能跟着一起排队——写文件的响应时间必须有上限。
        ReentrantLock lock = syncLocks.computeIfAbsent(uri, k -> new ReentrantLock());
        boolean locked;
        try {
            locked = lock.tryLock(sendSoftTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VERSION_UNSYNCED;
        }

        if (locked == false) {
            LOG.debug("LSP sync skipped for {}: another sync is still in flight", uri);
            return VERSION_UNSYNCED;
        }

        try {
            return syncFileInLock(uri, forceChange);
        } catch (LspBusyException e) {
            //服务器暂时跟不上：本轮算未同步（上层据此判「无结论」），但进程仍可用，下次再试
            LOG.debug("LSP sync deferred for {}: {}", uri, e.getMessage());
            return VERSION_UNSYNCED;
        } finally {
            lock.unlock();
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
            final DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, languageId, version, content));
            notifyServer("didOpen", () -> remoteServer.getTextDocumentService().didOpen(openParams));
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

        final DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                Arrays.asList(change));
        notifyServer("didChange", () -> remoteServer.getTextDocumentService().didChange(changeParams));
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
        return waitForDiagnosticsResult(uri, timeoutMs).getItems();
    }

    /**
     * 同 {@link #waitForDiagnostics}，并区分「已收到本轮推送」与「等待超时」。
     *
     * <p>只有拿到版本对齐（或时间上晚于本次同步）的推送才算确认；超时返回的列表可能是上一轮
     * 残留，故标记为未确认，由上层决定如何呈现这种「结果未知」。
     */
    @Override
    public LspDiagnosticsResult waitForDiagnosticsResult(String uri, long timeoutMs) {
        if (readyGate.isReady() == false) {
            //项目模型还没建好：此时服务器推的诊断是按「无 classpath 的孤立文件」算出来的，
            //连 JDK 类型与同文件字段都会报 cannot be resolved，一律不采信、也不往上报
            return LspDiagnosticsResult.unconfirmed(Collections.<Diagnostic>emptyList());
        }

        DiagState ds = diagStates.get(uri);
        if (ds == null) {
            //没有同步记录，谈不上「本轮」，一律按未确认处理
            return LspDiagnosticsResult.unconfirmed(Collections.<Diagnostic>emptyList());
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
                        //已收到本轮推送即为确认；去抖没走完只影响完整性，不影响结论有效性
                        return LspDiagnosticsResult.confirmed(ds.items);
                    }
                    doWait(Math.min(quiet, deadline - now));
                    continue;
                }

                long left = deadline - now;
                if (left <= 0) {
                    return LspDiagnosticsResult.unconfirmed(ds.items);
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
        return requestAsync(() -> remoteServer.getTextDocumentService().definition(params));
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return requestAsync(() -> remoteServer.getTextDocumentService().references(params));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return requestAsync(() -> remoteServer.getTextDocumentService().hover(params));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return requestAsync(() -> remoteServer.getTextDocumentService().documentSymbol(params));
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> workspaceSymbol(WorkspaceSymbolParams params) {
        return requestAsync(() -> remoteServer.getWorkspaceService().symbol(params));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        return requestAsync(() -> remoteServer.getTextDocumentService().implementation(params));
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        return requestAsync(() -> remoteServer.getTextDocumentService().prepareCallHierarchy(params));
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(String uri, int line, int offset) {
        return requestAsync(() -> remoteServer.getTextDocumentService()
                .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(line, offset))))
                .thenCompose(items -> {
                    if (items == null || items.isEmpty()) return CompletableFuture.completedFuture(null);
                    // 修正：使用正确的 LSP4J 方法名与包装参数
                    return requestAsync(() -> remoteServer.getTextDocumentService().callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.get(0))));
                });
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(String uri, int line, int offset) {
        return requestAsync(() -> remoteServer.getTextDocumentService()
                .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(line, offset))))
                .thenCompose(items -> {
                    if (items == null || items.isEmpty()) return CompletableFuture.completedFuture(null);
                    // 修正：使用正确的 LSP4J 方法名与包装参数
                    return requestAsync(() -> remoteServer.getTextDocumentService().callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.get(0))));
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
     * 优雅关闭（对齐 LSP 规范：shutdown -> exit -> destroy）。
     *
     * <p>已卡死的连接不再尝试优雅握手：往一个不读 stdin 的进程写 shutdown 只会再搭上
     * 一条线程，直接杀。可重入：重复调用不抛异常。
     */
    @Override
    public void shutdown() {
        boolean graceful = (closed == false) && (stalled == false) && (remoteServer != null)
                && (process == null || process.isAlive());

        if (graceful) {
            try {
                requestAsync(() -> remoteServer.shutdown()).get(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                notifyServer("exit", () -> remoteServer.exit());
            } catch (Throwable e) {
                LOG.debug("LSP graceful shutdown failed, force destroying: {}", e.getMessage());
            }
        }

        closeQuietly();
    }

    /**
     * 释放本客户端持有的一切资源（可重入）：发送线程、RPC 线程池、子进程、stderr 排空线程
     */
    private void closeQuietly() {
        closed = true;

        //先停发送：后续提交会直接被拒，而不是写进一个即将消失的管道
        sender.shutdownNow();

        destroyProcess();

        if (rpcExecutor != null) {
            rpcExecutor.shutdownNow();
        }

        joinStderrDrain();
    }

    /**
     * 结束子进程。关掉管道后，任何阻塞在 native write 的线程会立刻拿到 IOException 退出。
     */
    private void destroyProcess() {
        if (process == null) {
            return;
        }

        try {
            if (process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (Throwable e) {
            LOG.debug("Failed to destroy LSP process '{}': {}", serverName, e.getMessage());
        }
    }

    private void joinStderrDrain() {
        Thread drain = this.stderrDrain;
        if (drain == null) {
            return;
        }
        try {
            drain.join(STDERR_DRAIN_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
     * 取语言服务器 stderr 的尾部内容（用于错误诊断）。
     *
     * <p>直接读排空线程的缓冲，而不是去 {@code available()} 抢读管道：后者不仅会与排空
     * 线程互相抢字节，也只能看到调用那一瞬间残留在管道里的那一小段。
     */
    private String readStderr() {
        if (process == null) {
            return "";
        }

        //进程已退出时等排空线程读到 EOF，以免错过最后一批堆栈
        if (process.isAlive() == false) {
            joinStderrDrain();
        }

        synchronized (stderrTail) {
            return stderrTail.toString().trim();
        }
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