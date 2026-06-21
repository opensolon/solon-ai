package org.noear.solon.ai.loop.strategy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link ProcessBuilder} 的真实命令执行器。
 *
 * <p>对标 oh-my-claudecode 中 UltraQA 的子进程执行模式。
 * 支持超时控制、工作目录设置、标准输出/错误捕获。</p>
 *
 * <p>默认超时 5 分钟，可通过构造函数调整。</p>
 *
 * @since 4.0.4
 */
public class ProcessCommandExecutor implements CommandExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 300_000; // 5 分钟

    private final long timeoutMs;

    public ProcessCommandExecutor() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public ProcessCommandExecutor(long timeoutMs) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    @Override
    public boolean isAvailable() {
        try {
            // 检查是否可执行基本的 shell 命令
            if (isWindows()) {
                Process p = new ProcessBuilder("cmd", "/c", "echo available")
                        .redirectErrorStream(true)
                        .start();
                return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            } else {
                Process p = new ProcessBuilder("sh", "-c", "echo available")
                        .redirectErrorStream(true)
                        .start();
                return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CommandResult execute(String command, String workDir) throws IOException {
        if (command == null || command.trim().isEmpty()) {
            throw new IOException("Command must not be null or empty");
        }
        long startTime = System.currentTimeMillis();

        ProcessBuilder pb = buildProcess(command);

        // 设置工作目录
        if (workDir != null && !workDir.isEmpty()) {
            Path workPath = Paths.get(workDir);
            if (Files.exists(workPath) && Files.isDirectory(workPath)) {
                pb.directory(workPath.toFile());
            }
        }

        // 设置环境变量
        pb.environment().put("JAVA_TOOL_OPTIONS", ""); // 避免 Gradle 工具链警告

        // 合并错误流到标准输出（简化读取）
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 收集输出的线程
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });
        outputReader.setDaemon(true);
        outputReader.start();

        // 如果是独立错误流模式（redirectErrorStream=false），需要额外读取
        Thread errorReader = null;
        if (!pb.redirectErrorStream()) {
            errorReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });
            errorReader.setDaemon(true);
            errorReader.start();
        }

        // 等待进程完成（带超时）
        int exitCode;
        try {
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (finished) {
                exitCode = process.exitValue();
            } else {
                // 超时，强制终止
                process.destroyForcibly();
                exitCode = -1;
                output.append("\n[TIMEOUT] Command timed out after ").append(timeoutMs).append("ms\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            exitCode = -2;
            output.append("\n[INTERRUPTED] Command interrupted\n");
        }

        // 等待输出读取线程结束
        try {
            outputReader.join(1000);
            if (errorReader != null) errorReader.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        long executionTime = System.currentTimeMillis() - startTime;

        String stdout = output.toString();
        String stderr = errorOutput.toString();

        // 如果 stderr 为空但程序失败，尝试从 stdout 末尾提取错误信息
        if (exitCode != 0 && stderr.isEmpty() && stdout.contains("ERROR")) {
            // 对于合并模式，stderr 已经在 stdout 中，不需要额外处理
        }

        return new CommandResult(exitCode, stdout, stderr, executionTime);
    }

    /**
     * 根据操作系统构建 ProcessBuilder。
     */
    private ProcessBuilder buildProcess(String command) {
        if (isWindows()) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("sh", "-c", command);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
