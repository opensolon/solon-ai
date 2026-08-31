package org.noear.solon.ai.talents.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerminalSessionManager {

    public static final int DEFAULT_YIELD_TIME_MS = 1_000;
    public static final int DEFAULT_HARD_TIMEOUT_MS = 120_000;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 64_000;

    private static final Logger LOG = LoggerFactory.getLogger(TerminalSessionManager.class);
    private static final long DESTROY_GRACE_MS = 250L;
    private static final long COMPLETED_SESSION_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "solon-ai-command-timeout");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final ConcurrentMap<String, CommandSession> sessions = new ConcurrentHashMap<>();
    private final Charset outputCharset;
    private final ShellCommandFactory shellCommandFactory;

    public TerminalSessionManager() {
        this(ShellCommandFactory.detect(), StandardCharsets.UTF_8);
    }

    public TerminalSessionManager(ShellCommandFactory shellCommandFactory) {
        this(shellCommandFactory, StandardCharsets.UTF_8);
    }

    public TerminalSessionManager(Charset outputCharset) {
        this(ShellCommandFactory.detect(), outputCharset);
    }

    public TerminalSessionManager(ShellCommandFactory shellCommandFactory, Charset outputCharset) {
        this.shellCommandFactory =
                shellCommandFactory == null ? ShellCommandFactory.detect() : shellCommandFactory;
        this.outputCharset = outputCharset == null ? StandardCharsets.UTF_8 : outputCharset;
    }

    public CommandSnapshot exec(
            String command,
            Path workdir,
            Map<String, String> env,
            Integer yieldTimeMs,
            Integer maxOutputChars,
            Integer hardTimeoutMs)
            throws IOException {
        cleanupCompletedSessions();
        requireNonEmptyCommand(command);
        Path normalizedWorkdir = normalizeWorkdir(workdir);

        ShellCommandFactory.PreparedCommand prepared = null;
        try {
            // Windows：改用 ShellCommandFactory 的可靠启动方案（PowerShell 用 -EncodedCommand；
            // CMD 默认 /d /c 直连、仅多行/非 ANSI/超长命令才落 .bat），规避命令文本代码页转换问题；
            // 若产生临时脚本，由会话结束回调清理（异步会话下进程可能在本方法返回后仍在运行，不能提前删除）。
            // interactive=true：会话支持 bash_wait(chars) 写入，不能加 -NonInteractive，否则等待输入的命令会直接失败
            if (shellCommandFactory.isWindowsShell()) {
                prepared = shellCommandFactory.prepare(command, true);
            }
            List<String> argv = prepared != null ? prepared.argv() : shellCommandFactory.build(command);
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(normalizedWorkdir.toFile());
            builder.redirectErrorStream(true);
            // 注入实时系统 PATH（Windows：修复 JVM 环境快照不刷新导致新装命令不可见）；
            // 显式 env（如 PYTHON/NODE）优先级更高
            EnvironmentResolver.applyTo(builder, env);

            Process process = builder.start();
            String sessionId = newSessionId();
            Runnable cleanup = prepared != null ? prepared::cleanup : null;
            CommandSession session =
                    new CommandSession(
                            sessionId,
                            command,
                            normalizedWorkdir,
                            process,
                            System.currentTimeMillis(),
                            normalizeHardTimeoutMs(hardTimeoutMs),
                            outputCharset,
                            cleanup);
            sessions.put(sessionId, session);
            session.start();
            return waitAndSnapshot(session, yieldTimeMs, maxOutputChars);
        } catch (IOException e) {
            if (prepared != null) {
                prepared.cleanup();
            }
            throw e;
        }
    }

    public CommandSnapshot writeStdin(
            String sessionId, String chars, Integer yieldTimeMs, Integer maxOutputChars)
            throws IOException {
        cleanupCompletedSessions();
        CommandSession session = requireSession(sessionId);
        session.write(chars);
        return waitAndSnapshot(session, yieldTimeMs, maxOutputChars);
    }

    public CommandSnapshot terminate(String sessionId, String reason, Integer maxOutputChars) {
        cleanupCompletedSessions();
        CommandSession session = requireSession(sessionId);
        session.terminate(reason);
        return waitAndSnapshot(session, 2_000, maxOutputChars);
    }

    CommandSession getSessionForTest(String sessionId) {
        return sessions.get(sessionId);
    }

    private CommandSnapshot waitAndSnapshot(
            CommandSession session, Integer yieldTimeMs, Integer maxOutputChars) {
        int waitMs = normalizeYieldTimeMs(yieldTimeMs);
        try {
            session.exitFuture().get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
        } catch (Exception e) {
            LOG.debug("Command session wait failed: {}", e.getMessage());
        }
        session.enforceHardTimeout();
        session.awaitReaderIfCompleted(200);
        return session.snapshot(normalizeMaxOutputChars(maxOutputChars));
    }

    private CommandSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("session_id is required");
        }
        CommandSession session = sessions.get(sessionId.trim());
        if (session == null) {
            throw new IllegalArgumentException("Unknown command session: " + sessionId);
        }
        return session;
    }

    private void cleanupCompletedSessions() {
        long now = System.currentTimeMillis();
        sessions
                .entrySet()
                .removeIf(
                        entry -> {
                            CommandSession session = entry.getValue();
                            return session.completedAt() > 0
                                    && now - session.completedAt() > COMPLETED_SESSION_TTL_MS;
                        });
    }

    private static void requireNonEmptyCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
    }

    private static Path normalizeWorkdir(Path workdir) throws IOException {
        if (workdir == null) {
            throw new IllegalArgumentException("workdir is required");
        }
        Path normalized = workdir.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IOException("workdir does not exist: " + normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new IOException("workdir is not a directory: " + normalized);
        }
        return normalized;
    }

    private static int normalizeYieldTimeMs(Integer yieldTimeMs) {
        if (yieldTimeMs == null) {
            return DEFAULT_YIELD_TIME_MS;
        }
        return Math.max(0, yieldTimeMs);
    }

    private static int normalizeHardTimeoutMs(Integer hardTimeoutMs) {
        if (hardTimeoutMs == null || hardTimeoutMs <= 0) {
            return DEFAULT_HARD_TIMEOUT_MS;
        }
        return hardTimeoutMs;
    }

    private static int normalizeMaxOutputChars(Integer maxOutputChars) {
        if (maxOutputChars == null || maxOutputChars <= 0) {
            return DEFAULT_MAX_OUTPUT_CHARS;
        }
        return maxOutputChars;
    }

    private static String newSessionId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        Long pid = processPid(process);
        if (pid != null) {
            if (isWindows()) {
                // Windows：taskkill /T 依赖"活着的树根 PID"才能向下清理子树。
                // 必须在树根存活时先整树强杀，再销毁根进程；否则根进程先死，
                // 子进程将孤儿化残留（如 node/vite 继续监听端口），且无法再按根定位。
                destroyProcessTreeByPid(pid.longValue(), true);
                waitForProcess(process, DESTROY_GRACE_MS);
            } else {
                destroyProcessTreeByPid(pid.longValue(), false);
            }
        }
        process.destroy();
        waitForProcess(process, DESTROY_GRACE_MS);
        if (pid != null && isWindows() == false) {
            destroyProcessTreeByPid(pid.longValue(), true);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        waitForProcess(process, DESTROY_GRACE_MS);
    }

    private static Long processPid(Process process) {
        try {
            Method pidMethod = Process.class.getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
            }
        } catch (Throwable ignored) {
        }
        try {
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            Object value = pidField.get(process);
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void destroyProcessTreeByPid(long rootPid, boolean forcibly) {
        if (isWindows()) {
            destroyWindowsProcessTree(rootPid, forcibly);
            return;
        }
        List<Long> pids = collectUnixProcessTree(rootPid);
        Collections.reverse(pids);
        String signal = forcibly ? "-KILL" : "-TERM";
        for (Long pid : pids) {
            runQuietly(Arrays.asList("kill", signal, String.valueOf(pid)));
        }
    }

    private static void destroyWindowsProcessTree(long rootPid, boolean forcibly) {
        List<String> command = new ArrayList<>();
        command.add("taskkill");
        command.add("/T");
        if (forcibly) {
            command.add("/F");
        }
        command.add("/PID");
        command.add(String.valueOf(rootPid));
        runQuietly(command);
    }

    private static List<Long> collectUnixProcessTree(long rootPid) {
        List<Long> pids = new ArrayList<>();
        collectUnixProcessTree(rootPid, pids);
        return pids;
    }

    private static void collectUnixProcessTree(long pid, List<Long> pids) {
        pids.add(Long.valueOf(pid));
        for (Long childPid : listUnixChildPids(pid)) {
            collectUnixProcessTree(childPid.longValue(), pids);
        }
    }

    private static List<Long> listUnixChildPids(long pid) {
        List<Long> children = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("pgrep", "-P", String.valueOf(pid)).start();
            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        children.add(Long.valueOf(Long.parseLong(line.trim())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return children;
    }

    private static void waitForProcess(Process process, long timeoutMs) {
        try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runQuietly(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (Throwable e) {
            LOG.debug("Command failed silently {}: {}", command, e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean isWindows() {
        return EnvironmentResolver.isWindows();
    }

    public static final class CommandSnapshot {

        private final String sessionId;
        private final String command;
        private final Path workdir;
        private final boolean running;
        private final Integer exitCode;
        private final boolean timedOut;
        private final boolean terminated;
        private final String terminateReason;
        private final long wallTimeMs;
        private final int outputChars;
        private final int returnedChars;
        private final boolean outputTruncated;
        private final String output;

        CommandSnapshot(
                String sessionId,
                String command,
                Path workdir,
                boolean running,
                Integer exitCode,
                boolean timedOut,
                boolean terminated,
                String terminateReason,
                long wallTimeMs,
                int outputChars,
                int returnedChars,
                boolean outputTruncated,
                String output) {
            this.sessionId = sessionId;
            this.command = command;
            this.workdir = workdir;
            this.running = running;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.terminated = terminated;
            this.terminateReason = terminateReason;
            this.wallTimeMs = wallTimeMs;
            this.outputChars = outputChars;
            this.returnedChars = returnedChars;
            this.outputTruncated = outputTruncated;
            this.output = output;
        }

        public String sessionId() {
            return sessionId;
        }

        public String command() {
            return command;
        }

        public Path workdir() {
            return workdir;
        }

        public boolean running() {
            return running;
        }

        public Integer exitCode() {
            return exitCode;
        }

        public boolean timedOut() {
            return timedOut;
        }

        public boolean terminated() {
            return terminated;
        }

        public String terminateReason() {
            return terminateReason;
        }

        public long wallTimeMs() {
            return wallTimeMs;
        }

        public int outputChars() {
            return outputChars;
        }

        public int returnedChars() {
            return returnedChars;
        }

        public boolean outputTruncated() {
            return outputTruncated;
        }

        public String output() {
            return output;
        }
    }

    static final class CommandSession {

        private final String sessionId;
        private final String command;
        private final Path workdir;
        private final Process process;
        private final long startedAt;
        private final int hardTimeoutMs;
        private final Charset outputCharset;
        private final Runnable cleanup; // 会话结束后的临时脚本清理（Windows 脚本执行方案）
        private final Object lock = new Object();
        private final StringBuilder output = new StringBuilder();
        private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        private final CompletableFuture<Void> readerFuture = new CompletableFuture<>();
        private int nextOutputOffset;
        private volatile long completedAt;
        private volatile boolean timedOut;
        private volatile boolean terminated;
        private volatile String terminateReason;

        CommandSession(
                String sessionId,
                String command,
                Path workdir,
                Process process,
                long startedAt,
                int hardTimeoutMs,
                Charset outputCharset,
                Runnable cleanup) {
            this.sessionId = sessionId;
            this.command = command;
            this.workdir = workdir;
            this.process = process;
            this.startedAt = startedAt;
            this.hardTimeoutMs = hardTimeoutMs;
            this.outputCharset = outputCharset;
            this.cleanup = cleanup;
        }

        void start() {
            Thread reader = new Thread(this::readOutput, "solon-ai-command-reader-" + sessionId);
            reader.setDaemon(true);
            reader.start();
            TIMEOUT_EXECUTOR.schedule(this::enforceHardTimeout, hardTimeoutMs, TimeUnit.MILLISECONDS);
            Thread waiter = new Thread(this::waitForExit, "solon-ai-command-waiter-" + sessionId);
            waiter.setDaemon(true);
            waiter.start();
        }

        private void waitForExit() {
            try {
                int exitCode = process.waitFor();
                completedAt = System.currentTimeMillis();
                exitFuture.complete(Integer.valueOf(exitCode));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completedAt = System.currentTimeMillis();
                exitFuture.completeExceptionally(e);
            } catch (Throwable e) {
                completedAt = System.currentTimeMillis();
                exitFuture.completeExceptionally(e);
            } finally {
                runCleanup();
            }
        }

        private void runCleanup() {
            if (cleanup != null) {
                try {
                    cleanup.run();
                } catch (Throwable ignored) {
                    // 清理失败仅残留一个临时脚本文件，不影响会话结果
                }
            }
        }

        void write(String chars) throws IOException {
            if (chars == null || chars.isEmpty()) {
                return;
            }
            if (isRunning() == false) {
                throw new IOException("Process is not running: " + sessionId);
            }
            OutputStream stdin = process.getOutputStream();
            stdin.write(chars.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        void terminate(String reason) {
            if (isRunning()) {
                terminated = true;
                terminateReason = reason == null || reason.trim().isEmpty() ? "requested" : reason;
                destroyProcessTree(process);
            }
        }

        void enforceHardTimeout() {
            if (isRunning() == false) {
                return;
            }
            if (System.currentTimeMillis() - startedAt >= hardTimeoutMs) {
                timedOut = true;
                terminateReason = "hard_timeout_ms=" + hardTimeoutMs;
                destroyProcessTree(process);
            }
        }

        void awaitReaderIfCompleted(long timeoutMs) {
            if (exitFuture.isDone() == false) {
                return;
            }
            try {
                readerFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }

        CommandSnapshot snapshot(int maxOutputChars) {
            // 解码由读取线程增量完成（O(n)，多字节字符跨分块由 OutputDecoder 保留残字节处理）；
            // 这里只在锁内读取已解码文本并推进「已消费字符偏移」——偏移的读-改-写必须与读取同处一个
            // 临界区，否则并发调用（如 bash_wait 与 bash_stop 同时发生）会重复返回或漏掉一段输出
            String outputText;
            int outputLength;
            boolean truncated = false;
            synchronized (lock) {
                outputLength = output.length();
                int start = Math.min(nextOutputOffset, outputLength);
                int returnedLength = outputLength - start;
                if (returnedLength > maxOutputChars) {
                    outputText =
                            output.substring(outputLength - maxOutputChars, outputLength)
                                    + "\n... [output truncated to last "
                                    + maxOutputChars
                                    + " chars]";
                    truncated = true;
                } else {
                    outputText = output.substring(start, outputLength);
                }
                nextOutputOffset = outputLength;
            }
            Integer exitCode = null;
            if (exitFuture.isDone()) {
                try {
                    exitCode = exitFuture.getNow(null);
                } catch (Throwable ignored) {
                }
            }
            return new CommandSnapshot(
                    sessionId,
                    command,
                    workdir,
                    isRunning(),
                    exitCode,
                    timedOut,
                    terminated,
                    terminateReason,
                    System.currentTimeMillis() - startedAt,
                    outputLength,
                    outputText.length(),
                    truncated,
                    outputText);
        }

        CompletableFuture<Integer> exitFuture() {
            return exitFuture;
        }

        long completedAt() {
            return completedAt;
        }

        boolean isRunning() {
            return process.isAlive();
        }

        private void readOutput() {
            // 字节层读取 + 增量解码：解码器（仅本线程使用）内部保留不完整的多字节序列，
            // 因此 UTF-8 中文不会被 4096 分块边界切断；同时具备 ANSI 代码页兜底能力
            OutputDecoder decoder = new OutputDecoder(outputCharset);
            // PowerShell 会向 stderr 写 CLIXML 块（参见 CliXmlFilter）：必须在解码前剥离，
            // 否则一条流里混着两种编码，字符集锁定后必有一半变乱码
            CliXmlFilter cliXmlFilter = CliXmlFilter.isNeeded() ? new CliXmlFilter() : null;
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    byte[] chunk = buffer;
                    int len = n;
                    if (cliXmlFilter != null) {
                        chunk = cliXmlFilter.accept(buffer, n);
                        len = chunk.length;
                        if (len == 0) {
                            continue;
                        }
                    }
                    String text = decoder.decode(chunk, len);
                    if (text.isEmpty() == false) {
                        synchronized (lock) {
                            output.append(text);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.debug("Command output reader stopped for {}: {}", sessionId, e.getMessage());
                readerFuture.completeExceptionally(e);
            } finally {
                StringBuilder rest = new StringBuilder();
                if (cliXmlFilter != null) {
                    byte[] pending = cliXmlFilter.flush();
                    if (pending.length > 0) {
                        rest.append(decoder.decode(pending, pending.length));
                    }
                }
                rest.append(decoder.flush());
                // CLIXML 里的 error/warning 文本单独解码后补在末尾
                if (cliXmlFilter != null) {
                    rest.append(cliXmlFilter.drainMessages());
                }
                if (rest.length() > 0) {
                    synchronized (lock) {
                        output.append(rest);
                    }
                }
                if (readerFuture.isDone() == false) {
                    readerFuture.complete(null);
                }
            }
        }
    }
}
