package org.noear.solon.ai.loop.strategy;

import java.io.IOException;

/**
 * 命令执行器接口 —— 对标 oh-my-claudecode 中 UltraQA 的真实验证命令执行。
 *
 * <p>抽象命令执行能力，支持真实 Process 执行和模拟执行两种模式。
 * 核心语义：给定命令返回执行结果（含退出码、标准输出、错误输出）。</p>
 *
 * @since 4.0.4
 */
public interface CommandExecutor {

    /**
     * 执行结果。
     */
    class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final long executionTimeMs;

        public CommandResult(int exitCode, String stdout, String stderr, long executionTimeMs) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.executionTimeMs = executionTimeMs;
        }

        public boolean isSuccess() { return exitCode == 0; }
        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public long getExecutionTimeMs() { return executionTimeMs; }

        /**
         * 获取单行摘要（截取 stdout 或 stderr 的第一行）。
         */
        public String getSummary() {
            if (stdout != null && !stdout.isEmpty()) {
                String firstLine = stdout.split("\\n", 2)[0].trim();
                if (!firstLine.isEmpty()) return firstLine;
            }
            if (stderr != null && !stderr.isEmpty()) {
                String firstLine = stderr.split("\\n", 2)[0].trim();
                if (!firstLine.isEmpty()) return "[err] " + firstLine;
            }
            return isSuccess() ? "OK" : "Failed (exit: " + exitCode + ")";
        }

        /**
         * 获取详细诊断信息（退出码+错误输出）。
         */
        public String getDiagnosis() {
            StringBuilder sb = new StringBuilder();
            sb.append("exit code: ").append(exitCode);
            if (stderr != null && !stderr.isEmpty()) {
                sb.append(", stderr: ").append(stderr.length() > 200 ? stderr.substring(0, 200) + "..." : stderr);
            }
            return sb.toString();
        }
    }

    /**
     * 执行命令并返回结果。
     *
     * @param command 命令字符串（如 "mvn test"）
     * @param workDir 工作目录，null 则使用当前目录
     * @return 命令执行结果
     */
    CommandResult execute(String command, String workDir) throws IOException;

    /**
     * 执行命令（使用当前工作目录）。
     */
    default CommandResult execute(String command) throws IOException {
        return execute(command, null);
    }

    /**
     * 检查执行器是否可用（如 Maven 是否已安装）。
     */
    default boolean isAvailable() {
        return true;
    }
}
