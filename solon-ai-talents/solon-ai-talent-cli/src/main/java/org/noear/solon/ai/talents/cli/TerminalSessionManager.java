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

    public TerminalSessionManager() {
        this(StandardCharsets.UTF_8);
    }

    public TerminalSessionManager(Charset outputCharset) {
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
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
        builder.directory(normalizedWorkdir.toFile());
        builder.redirectErrorStream(true);
        if (env != null && env.isEmpty() == false) {
            builder.environment().putAll(env);
        }

        Process process = builder.start();
        String sessionId = newSessionId();
        CommandSession session =
                new CommandSession(
                        sessionId,
                        command,
                        normalizedWorkdir,
                        process,
                        System.currentTimeMillis(),
                        normalizeHardTimeoutMs(hardTimeoutMs),
                        outputCharset);
        sessions.put(sessionId, session);
        session.start();
        return waitAndSnapshot(session, yieldTimeMs, maxOutputChars);
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

    private static List<String> shellCommand(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return Arrays.asList("cmd", "/c", command);
        }
        return Arrays.asList(probeUnixShell(), "-lc", command);
    }

    private static String probeUnixShell() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok ? "bash" : "/bin/sh";
        } catch (Throwable e) {
            return "/bin/sh";
        }
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
            destroyProcessTreeByPid(pid.longValue(), false);
        }
        process.destroy();
        waitForProcess(process, DESTROY_GRACE_MS);
        if (pid != null) {
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
        return System.getProperty("os.name").toLowerCase().contains("win");
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
                Charset outputCharset) {
            this.sessionId = sessionId;
            this.command = command;
            this.workdir = workdir;
            this.process = process;
            this.startedAt = startedAt;
            this.hardTimeoutMs = hardTimeoutMs;
            this.outputCharset = outputCharset;
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
            try (InputStream input = process.getInputStream();
                    InputStreamReader reader = new InputStreamReader(input, outputCharset)) {
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    synchronized (lock) {
                        output.append(buffer, 0, n);
                    }
                }
            } catch (IOException e) {
                LOG.debug("Command output reader stopped for {}: {}", sessionId, e.getMessage());
                readerFuture.completeExceptionally(e);
            } finally {
                if (readerFuture.isDone() == false) {
                    readerFuture.complete(null);
                }
            }
        }
    }
}
