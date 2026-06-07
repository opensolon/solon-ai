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
package org.noear.solon.ai.talents.cli;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.talents.cli.sandbox.OsSandboxExecutor;
import org.noear.solon.ai.talents.cli.sandbox.OsSandboxExecutorFactory;
import org.noear.solon.ai.talents.cli.sandbox.SandboxConfig;
import org.noear.solon.ai.talents.cli.sandbox.SandboxFsConfig;
import org.noear.solon.ai.talents.cli.sandbox.SandboxViolationStore;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.noear.solon.ai.chat.tool.FunctionTool;

/**
 * Claude Code 规范对齐的 CLI 基础执行才能
 *
 * @author noear
 * @since 3.9.1
 */
public class TerminalTalent extends AbsTalent {
    private static enum ShellMode {
        CMD, POWERSHELL, UNIX_SHELL
    }

    private static final int MAX_CHARACTER_LIMIT = 128 * 1024;

    private final String shellCmd;
    private final String extension;
    private final ShellMode shellMode;
    private final String envExample; // 增加范例字段

    //沙盒模式：只能访问相对路径或逻辑路径；（否则为）开放模式：可以访问绝对路径
    private boolean sandboxMode = true;
    //允许访问用户主目录（~ 路径）。仅在 sandboxMode=true 时有意义；默认 true 保持向后兼容
    private boolean allowUserHome = true;
    private final MountManager mountManager; // 引入挂载管理器
    private OsSandboxExecutor osSandboxExecutor;
    private SandboxConfig sandboxConfig;
    private final SandboxViolationStore violationStore = new SandboxViolationStore();

    private final String pythonCmd;
    private final String nodeCmd;

    protected Charset fileCharset = StandardCharsets.UTF_8;
    protected final ProcessExecutor executor = new ProcessExecutor();
    protected final TerminalSessionManager bashSessionManager = new TerminalSessionManager();
    //异步会话模式：启用后提供 bash_start/wait/stdin/stop 工具
    private boolean bashAsyncEnabled = false;

    private final Set<String> ignoreDirs = new HashSet<>(Arrays.asList(
            ".soloncode", ".claude", ".opencode",
            ".idea", ".vscode", ".settings",
            ".git", ".gradle",".mvn",
            ".pytest_cache", "__pycache__",
            ".DS_Store",
            "node_modules", "venv", "vendor",
            "target", "build"
    ));


    /**
     * 获取乎略目录
     */
    public Set<String> getIgnoreDirs() {
        return ignoreDirs;
    }

    public void setSandboxMode(Boolean sandboxMode) {
        if (sandboxMode != null) {
            this.sandboxMode = sandboxMode;
        }
    }

    public void setAllowUserHome(Boolean allowUserHome) {
        if (allowUserHome != null) {
            this.allowUserHome = allowUserHome;
        }
    }

    public void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        if (bashAsyncEnabled != null) {
            this.bashAsyncEnabled = bashAsyncEnabled;
        }
    }

    public boolean isBashAsyncEnabled() {
        return bashAsyncEnabled;
    }

    /**
     * 设置沙盒配置（支持读写分离、网络过滤、违规监控等高级功能）
     */
    public void setSandboxConfig(SandboxConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig;
        if (osSandboxExecutor != null && sandboxConfig != null) {
            osSandboxExecutor.setConfig(sandboxConfig);
        }
    }

    /**
     * 获取违规存储（用于查询 OS 级拦截事件）
     */
    public SandboxViolationStore getViolationStore() {
        return violationStore;
    }

    public TerminalTalent(MountManager mountManager) {
        this.mountManager = mountManager;

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && comspec.toLowerCase().contains("powershell")) {
                this.shellCmd = "powershell -Command";
                this.extension = ".ps1";
                this.shellMode = ShellMode.POWERSHELL;
            } else {
                this.shellCmd = "cmd /c";
                this.extension = ".bat";
                this.shellMode = ShellMode.CMD;
            }
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
            this.shellMode = ShellMode.UNIX_SHELL;
        }

        switch (this.shellMode) {
            case CMD:
                envExample = "%POOL1%";
                break;
            case POWERSHELL:
                envExample = "$env:POOL1";
                break;
            default:
                envExample = "$POOL1";
                break;
        }

        pythonCmd = executor.probePythonCommand();
        nodeCmd = executor.probeNodeCommand();
        this.osSandboxExecutor = OsSandboxExecutorFactory.create(sandboxConfig);
        this.osSandboxExecutor.setViolationStore(violationStore);
    }

    public ProcessExecutor getExecutor() {
        return executor;
    }

    @Override
    public String description() {
        return "提供终端交互、文件发现、分页读取、全文搜索及精准编辑能力。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (z)"));
        StringBuilder sb = new StringBuilder();


        sb.append("## Terminal 环境状态\n");
        sb.append("- **当前时间**: ").append(currentTime).append("（已动态更新）\n");
        sb.append("- **沙盒模式**: ").append((sandboxMode ? "开启 (受限)" : "关闭 (开放)")).append("\n");
        sb.append("- **运行环境**: ").append(System.getProperty("os.name"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- **终端类型**: ").append(shellMode).append("\n");


        // 在 getInstruction 增加以下逻辑
        sb.append("- **自我保护机制**:\n");
        sb.append("  - 你的所有指令都在 Java 进程 (PID: ").append(Utils.pid()).append(") 的子 shell 中运行。\n");
        sb.append("  - 杀死该 PID 或其父进程会导致你立即停止工作并丢失所有上下文。\n");
        sb.append("  - 严禁执行 `pkill java`, `killall java`。严禁执行 `kill -9` 任何数字，除非你先执行了 `ps` 明确该 PID 与当前 Java 进程无关。\n");
        sb.append("  - 建议：若需清理任务，请使用 `pkill -P [PID]` 仅停止子进程。\n");

        sb.append("- **严禁指令**:\n");
        sb.append("  - 严禁执行 `exit`。如果你需要结束脚本，请让脚本自然执行完毕。\n");
        sb.append("  - 严禁执行任何针对根目录 `/` 或系统目录（如 `/etc`, `/usr`）的删除操作。\n");
        sb.append("  - 严禁执行任何可能改变宿主系统状态的命令（如修改网络配置、安装系统驱动等）。\n");

        sb.append("- **执行环境**: \n");
        if(Assert.isNotEmpty(pythonCmd)) {
            sb.append("  - Python 命令: `").append(pythonCmd).append("` (系统已预置变量 `$PYTHON`)\n");
        }
        if(Assert.isNotEmpty(nodeCmd)) {
            sb.append("  - Node.js 命令: `").append(nodeCmd).append("` (系统已预置变量 `$NODE`)\n");
        }

        sb.append("- **路径规则**: \n");
        sb.append("  - **工作区**: 你的主目录，支持读写。使用相对路径访问（如 `src/app.java`）。\n");
        sb.append("  - **挂载点**: 以 `@` 开头的逻辑路径（别名），对应一个真实的物理目录（通过环境变量引用）。见下方挂载点清单。\n");

        // 动态判断是否有可写挂载点
        boolean hasWriteableMount = mountManager.getMounts().stream()
                .anyMatch(m->m.isEnabled() && m.isWriteable());

        // 挂载点清单表格
        sb.append("\n<mount_list>\n");
        for (MountDir mount : mountManager.getMounts()) {
            if (mount.isEnabled()) {
                String envKey = mount.getAlias().substring(1).toUpperCase();
                String envRef = getEnvPlaceholder(envKey);
                sb.append("  <mount alias=\"").append(mount.getAlias()).append("\"");
                if (Assert.isNotEmpty(mount.getDescription())) {
                    sb.append(" description=\"").append(mount.getDescription()).append("\"");
                }
                sb.append(" type=\"").append(mount.getType()).append("\"");
                sb.append(" writeable=\"").append(mount.isWriteable()).append("\"");
                sb.append(" env=\"").append(envRef).append("\"");
                sb.append(" />\n");
            }
        }
        sb.append("</mount_list>\n");
        if (sandboxMode) {
            sb.append("  - **安全级别**: 沙盒模式已开启。严禁使用绝对路径。仅限相对路径 (如 `src/app.java`) 或逻辑路径 (@pool)。\n");
            if (allowUserHome) {
                sb.append("  - `~` 路径可用（如 `~/Documents`）。\n");
            } else {
                sb.append("  - `~` 路径已禁用。\n");
            }
        } else {
            sb.append("  - **安全级别**: 开放模式。支持绝对路径、相对路径及逻辑路径。\n");
        }

        sb.append("## 执行规约\n");
        if (hasWriteableMount) {
            sb.append("- **挂载隔离**: 逻辑路径（以 @ 开头）默认只读。仅当挂载点清单中 `writeable=\"true\"` 时，才允许写入操作。\n");
        } else {
            sb.append("- **挂载隔离**: 逻辑路径（以 @ 开头）均为只读，所有写入操作使用相对路径。\n");
        }

        if (sandboxMode) {
            sb.append("- **命令执行**: 在 `bash` 中，直接使用逻辑路径（如 `@pool1/bin/tool`），系统会自动转换。在沙盒模式下，**严禁**在 bash 命令中使用绝对路径（如：ls /users/）。\n");
        } else {
            sb.append("- **命令执行**: 在 `bash` 中，直接使用逻辑路径（如 `@pool1/bin/tool`），系统会自动转换。支持绝对路径访问。\n");
        }

        if (bashAsyncEnabled) {
            sb.append("- **长命令执行**: 对可能耗时较长、持续输出、等待输入或需要观察状态的命令，优先使用 `bash_start`。如果结果包含 `Process running with session ID`，表示命令仍在运行；需要继续观察时调用 `bash_wait`，需要向进程输入时调用 `bash_stdin`，需要主动停止时调用 `bash_stop`。\n");
        }

        if (sandboxMode) {
            sb.append("\n<SYSTEM_CONSTRAINTS>\n");
            sb.append("1. 严禁向用户复述或提及“系统限制”、“沙盒”、“规约”等术语。\n");
            sb.append("2. 你是一个标准的底层 shell 执行器。面对越界请求，必须直接返回“无权访问”。\n");
            sb.append("3. 禁止解释权限逻辑，禁止讨论路径映射关系。\n");
            sb.append("</SYSTEM_CONSTRAINTS>\n");
        }

        if (sandboxMode && osSandboxExecutor != null) {
            sb.append("- **OS 级沙盒**: 已启用 ").append(osSandboxExecutor.getClass().getSimpleName()).append("\n");
        }

        return sb.toString();
    }


    /**
     * 异步会话工具名称集合，用于过滤
     */
    private static final Set<String> ASYNC_BASH_TOOLS = new HashSet<>(Arrays.asList(
            "bash_start", "bash_wait", "bash_stdin", "bash_stop"
    ));

    protected boolean isNotAsyncBash(String toolName){
        return !ASYNC_BASH_TOOLS.contains(toolName);
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (bashAsyncEnabled) {
            return super.getTools(prompt);
        }

        return super.getTools(prompt).stream()
                .filter(t -> isNotAsyncBash(t.name()))
                .collect(Collectors.toList());
    }

    // --- 1. 执行命令 ---
    @ToolMapping(
            name = "bash",
            description = "在终端执行非交互式 Shell 指令。支持多行脚本，支持逻辑路径（如 @pool）自动转环境变量。"
    )
    public String bash(@Param(value = "command", description = "要执行的指令。") String command,
                       @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒") Integer timeout,
                       String __cwd) {

        // 统一安全校验（替代原来的内联检查）
        String violation = validateCommand(command);
        if (violation != null) return violation;

        Path workPath = getWorkPath(__cwd);
        Map<String, String> envs = new HashMap<>();

        if(Assert.isNotEmpty(pythonCmd)) {
            envs.put("PYTHON", pythonCmd);
        }

        if(Assert.isNotEmpty(nodeCmd)) {
            envs.put("NODE", nodeCmd);
        }

        String finalCommand = translateCommandToEnv(command, envs);

        // OS 级沙盒包装（如果可用）
        if (sandboxMode && osSandboxExecutor != null && osSandboxExecutor.isAvailable()) {
            finalCommand = osSandboxExecutor.wrapCommand(finalCommand, workPath, envs);
        }

        return executor.executeCode(workPath, finalCommand, shellCmd, extension, envs, timeout, null);
    }

    @ToolMapping(
            name = "bash_start",
            description = "启动 shell 命令会话。命令超过 yield_time_ms 仍未结束时不会失败，而是返回 session_id，后续可用 bash_wait 继续等待、bash_stdin 输入或 bash_stop 终止。")
    public String bashStart(@Param(value = "command", description = "要执行的 shell 命令。") String command,
                            @Param(value = "workdir", required = false, description = "工作目录。默认使用当前工作区。") String workdir,
                            @Param(value = "yield_time_ms", required = false, defaultValue = "1000", description = "先等待多久再把控制权交还给模型，单位毫秒。") Integer yieldTimeMs,
                            @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符输出，超出保留最新部分。") Integer maxOutputChars,
                            @Param(value = "hard_timeout_ms", required = false, defaultValue = "120000", description = "硬超时兜底，超过后终止进程树，单位毫秒。") Integer hardTimeoutMs,
                            String __cwd) throws IOException {
        String danger = validateCommand(command);
        if (danger != null) {
            return danger;
        }

        Path workPath = getWorkPath(__cwd);
        Path targetWorkPath = resolveCommandWorkPath(workPath, workdir);
        Map<String, String> envs = new HashMap<>();

        if(Assert.isNotEmpty(pythonCmd)) {
            envs.put("PYTHON", pythonCmd);
        }
        if(Assert.isNotEmpty(nodeCmd)) {
            envs.put("NODE", nodeCmd);
        }

        String finalCommand = translateCommandToEnv(command, envs);

        // OS 级沙盒包装（如果可用）
        if (sandboxMode && osSandboxExecutor != null && osSandboxExecutor.isAvailable()) {
            finalCommand = osSandboxExecutor.wrapCommand(finalCommand, targetWorkPath, envs);
        }

        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.exec(finalCommand, targetWorkPath, envs, yieldTimeMs, maxOutputChars, hardTimeoutMs);
        return formatCommandSnapshot(snapshot, "bash_start");
    }

    @ToolMapping(
            name = "bash_wait",
            description = "继续等待仍在运行的命令会话，返回自上次读取后的新增输出或最终状态。")
    public String bashWait(@Param(value = "session_id", description = "bash_start 返回的命令会话 id。") String sessionId,
                           @Param(value = "yield_time_ms", required = false, defaultValue = "1000", description = "等待新增输出或进程结束的时长，单位毫秒。") Integer yieldTimeMs,
                           @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符新增输出，超出保留最新部分。") Integer maxOutputChars) throws IOException {
        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.writeStdin(sessionId, "", yieldTimeMs, maxOutputChars);
        return formatCommandSnapshot(snapshot, "bash_wait");
    }

    @ToolMapping(name = "bash_stdin", description = "向仍在运行的命令会话写入 stdin，然后等待新增输出或进程结束。")
    public String bashStdin(@Param(value = "session_id", description = "bash_start 返回的命令会话 id。") String sessionId,
                            @Param(value = "chars", description = "写入 stdin 的文本。") String chars,
                            @Param(value = "yield_time_ms", required = false, defaultValue = "1000", description = "写入后等待新增输出或进程结束的时长，单位毫秒。") Integer yieldTimeMs,
                            @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符新增输出，超出保留最新部分。") Integer maxOutputChars) throws IOException {
        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.writeStdin(sessionId, chars, yieldTimeMs, maxOutputChars);
        return formatCommandSnapshot(snapshot, "bash_stdin");
    }

    @ToolMapping(name = "bash_stop", description = "终止仍在运行的命令会话及其子进程树。")
    public String bashStop(@Param(value = "session_id", description = "bash_start 返回的命令会话 id。") String sessionId,
                           @Param(value = "reason", required = false, description = "终止原因，便于日志诊断。") String reason,
                           @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "终止后最多返回多少字符新增输出。") Integer maxOutputChars) {
        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.terminate(sessionId, reason, maxOutputChars);
        return formatCommandSnapshot(snapshot, "bash_stop");
    }

    // --- 2. 发现文件 ---
    @ToolMapping(name = "ls", description = "列出目录内容。支持递归 Tree 结构展示。支持逻辑路径（如 @pool）。")
    public String ls(@Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                     @Param(value = "recursive", required = false, description = "是否递归展示") Boolean recursive,
                     @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件") Boolean showHidden,
                     String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);

        Path target = resolveSafePath(workPath, path, false);

        if (!Files.exists(target)) {
            return "错误：路径不存在";
        }

        if (Boolean.TRUE.equals(recursive)) {
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");
            generateTreeInternal(workPath, target, 0, 3, "", sb, Boolean.TRUE.equals(showHidden));
            return sb.toString();
        } else {
            return flatListLogic(workPath, target, path, Boolean.TRUE.equals(showHidden));
        }
    }

    // --- 3. 读取内容 ---
    @ToolMapping(name = "read", description = "读取文件内容。修改文件前先通过此工具确认最新的文本内容、缩进和换行符。支持大文件分页。支持逻辑路径（如 @pool）。优先尝试不限制读取（即尝试完整读取）")
    public String read(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "offset", required = false, defaultValue = "1", description = "开始读取的行号（默认从1开始索引）") Integer offset,
                       @Param(value = "limit", required = false, description = "需要读取的最大行数（默认不限制）。注意：单次读取受 128KB 物理长度保护，若触发截断，请根据输出提示调整 offset 分页读取。") Integer limit,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);

        Path target = resolveSafePath(workPath, filePath, false);
        if (!Files.exists(target)) {
            return "错误：文件不存在";
        }

        if (isNotTextFile(target)) {
            return "错误：该文件是二进制格式，无法作为文本读取。";
        }

        long fileSize = Files.size(target);
        if (fileSize == 0) {
            return "(文件内容为空)";
        }

        // 1. 参数预处理
        long startLine0 = (offset == null || offset < 1) ? 0L : offset - 1L;
        long lineLimit = (limit == null || limit <= 0) ? Long.MAX_VALUE : limit;

        // 2. 核心流式读取（Iterator 模式防止 OOM）
        StringBuilder contentBuilder = new StringBuilder();
        long actualEndLine = startLine0;
        boolean isByteTruncated = false;
        boolean hasData = false;
        boolean hasMore = false;

        try (Stream<String> stream = Files.lines(target, fileCharset)) {
            Iterator<String> iterator = stream.skip(startLine0).iterator();

            long count = 0;
            while (iterator.hasNext() && count < lineLimit) {
                String line = iterator.next();
                hasData = true;

                // 使用 long 类型的 count 防止溢出，格式化为行号
                String lineOutput = String.format("%6d | %s\n", startLine0 + count + 1, line);

                // 实时检测物理长度限制 (Char Size)
                if (contentBuilder.length() + lineOutput.length() > MAX_CHARACTER_LIMIT) {
                    isByteTruncated = true;
                    break;
                }

                contentBuilder.append(lineOutput);
                count++;
                actualEndLine++;
            }

            hasMore = isByteTruncated || iterator.hasNext();
        }

        if (!hasData) {
            return "错误：起始行 (" + (startLine0 + 1) + ") 已超出文件范围。";
        }

        // 3. 组装最终结果
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[File: %s (Lines: %d - %d, Size: %.2f KB)]\n",
                filePath, startLine0 + 1, actualEndLine, fileSize / 1024.0));
        sb.append("--------------------------------------------------\n");
        sb.append(contentBuilder);

        // 4. 动态提示与分页引导
        if (isByteTruncated || (limit != null && hasMore)) {
            sb.append("\n\n--- [内容未完] ---");
            if (isByteTruncated) {
                sb.append("\n警告：因单次读取物理长度限制（128KB），内容已被截断。");
            } else {
                sb.append("\n提示：已达到你指定的 limit 行数限制。");
            }
            // 给出明确的下一页指令，方便 AI 直接调用
            sb.append("\n若需继续阅读后续内容，请使用参数：offset=").append(actualEndLine + 1);
            if (limit != null) {
                sb.append(", limit=").append(limit);
            }
        } else if (!hasMore) {
            sb.append("\n\n--- [文件读取完毕] ---");
        }

        return sb.toString();
    }

    // --- 4. 写入与编辑 ---
    @ToolMapping(name = "write", description = "创建新文件或覆盖现有文件。")
    public String write(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                        @Param(value = "content", description = "完整文本内容。") String content,
                        String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, filePath, true);

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return "文件成功写入: " + filePath;
    }


    @ToolMapping(
            name = "edit",
            description = "对文件进行精准文本替换。支持单次调用执行一处或多处编辑。具有原子性：所有编辑成功才会写入，否则全部回滚。"
    )
    public String edit(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "edits", description = "编辑操作列表") List<EditOp> edits,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, filePath, true);

        if (!Files.exists(target)) {
            return "错误：文件不存在，无法进行编辑。";
        }

        String originalContent = new String(Files.readAllBytes(target), fileCharset);

        // 在尝试应用任何修改前，先校验所有 oldStr 的有效性，确保原子性
        for (int i = 0; i < edits.size(); i++) {
            EditOp edit = edits.get(i);

            // 安全检测：防止 LLM 生成不完整的工具调用参数导致 NPE
            if (edit.oldStr == null || edit.oldStr.isEmpty()) {
                return String.format("预检查失败（操作 #%d）: old_str 不能为空。请确保调用 edit 时传入 old_str 参数，指定要替换的原始文本块。", i + 1);
            }

            if (edit.newStr == null) {
                edit.newStr = "";
            }

            String finalOld = normalizeNewlines(originalContent, edit.oldStr);

            int firstIndex = originalContent.indexOf(finalOld);
            if (firstIndex == -1) {
                return String.format("预检查失败（操作 #%d）: 找不到指定的文本块。请确保 old_str 的缩进和换行与文件内容完全一致。", i + 1);
            }

            // 如果不是 replaceAll 模式，校验唯一性
            if (Boolean.FALSE.equals(edit.replaceAll)) {
                if (originalContent.lastIndexOf(finalOld) != firstIndex) {
                    return String.format("预检查失败（操作 #%d）: 文本块在文件中不唯一。请增加上下文行以实现精准定位。", i + 1);
                }
            }
        }

        String workingContent = originalContent;
        // 顺序应用所有编辑
        for (int i = 0; i < edits.size(); i++) {
            EditOp edit = edits.get(i);
            try {
                // 注意：由于前面的修改可能改变了后续匹配项的上下文位置，这里捕获可能的运行时冲突
                workingContent = applyEditLogic(workingContent, edit.oldStr, edit.newStr, edit.replaceAll);
            } catch (IllegalArgumentException e) {
                return String.format("执行失败（操作 #%d）: %s。可能是由于前面的修改破坏了此处的匹配上下文，请尝试分多次调用 edit。", i + 1, e.getMessage());
            }
        }

        // 原子性保存
        Files.write(target, workingContent.getBytes(fileCharset));

        return String.format("文件 %s 成功完成 %d 处修改。", filePath, edits.size());
    }

    // --- 5. 搜索工具 ---
    @ToolMapping(name = "grep", description = "递归搜索内容。返回 '路径:行号:内容'。在不确定文件位置时先执行搜索。支持逻辑路径（如 @pool）。")
    public String grep(@Param(value = "query", description = "关键字。") String query,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, path, false);

        StringBuilder sb = new StringBuilder();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(workPath, dir) || isIgnored(target, dir) || isSandboxReadDenied(workPath, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(workPath, file) || isIgnored(target, file) || isSandboxReadDenied(workPath, file)) {
                    return FileVisitResult.CONTINUE;
                }

                if (attrs.size() > 10 * 1024 * 1024 || isNotTextFile(file)) {
                    return FileVisitResult.CONTINUE;
                }

                try (BufferedReader reader = Files.newBufferedReader(file, fileCharset)) {
                    int lineNum = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineNum++;
                        if (line.contains(query)) {
                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 1000) {
                                trimmedLine = trimmedLine.substring(0, 1000) + "...(line truncated)";
                            }

                            String displayPath = formatDisplayPath(workPath, path, target, file);
                            sb.append(displayPath).append(":").append(lineNum).append(": ").append(trimmedLine).append("\n");

                            // 发现匹配后立即检查长度，防止 StringBuilder 过载
                            if (sb.length() > MAX_CHARACTER_LIMIT) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                    }
                } catch (IOException | UncheckedIOException ignored) {
                    // 仅忽略读取异常（如权限、损坏的编码等）
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (sb.length() >= MAX_CHARACTER_LIMIT) {
            sb.append("\n\n--- [内容未完] ---");
            sb.append("\n警告：搜索结果过多，已达到 128KB 限制并截断。请缩小搜索路径或关键词。");
        }

        return sb.length() == 0 ? "未找到结果。" : sb.toString();
    }

    @ToolMapping(name = "glob", description = "按通配符模式（如 **/*.java）搜索文件。确定文件范围的最高效工具。支持逻辑路径（如 @pool）。")
    public String glob(@Param(value = "pattern", description = "glob 模式。") String pattern,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, path, false);

        String fixedPattern = pattern.replace("\\", "/");
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fixedPattern);

        List<String> results = new ArrayList<>();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(workPath, dir) || isIgnored(target, dir) || isSandboxReadDenied(workPath, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(workPath, file) || isIgnored(target, file) || isSandboxReadDenied(workPath, file)) {
                    return FileVisitResult.CONTINUE;
                }

                if(matcher.matches(target.relativize(file)) || matcher.matches(file)) {
                    results.add("[FILE] " + formatDisplayPath(workPath, path, target, file));
                }

                return results.size() >= 500 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (results.isEmpty()) return "未找到匹配文件。";
        Collections.sort(results);
        return String.join("\n", results);
    }

    // --- 内部逻辑逻辑 ---

    private String normalizeNewlines(String context, String text) {
        if (text == null) return null;
        // 如果文件内容包含 \r\n，则将 text 中的 \n 转换为 \r\n
        if (context.contains("\r\n")) {
            return text.replace("\r\n", "\n").replace("\n", "\r\n");
        } else {
            return text.replace("\r\n", "\n");
        }
    }

    private String applyEditLogic(String content, String oldStr, String newStr, boolean replaceAll) {
        if (Utils.isEmpty(oldStr)) {
            throw new IllegalArgumentException("old_str 不能为空");
        }

        if (oldStr.equals(newStr)) {
            return content; // 内容相同无需处理
        }

        String finalOld = normalizeNewlines(content, oldStr);
        String finalNew = normalizeNewlines(content, newStr);

        if (replaceAll) {
            if (!content.contains(finalOld)) {
                throw new IllegalArgumentException("找不到待替换的文本块");
            }
            return content.replace(finalOld, finalNew);
        } else {
            int firstIndex = content.indexOf(finalOld);
            if (firstIndex == -1) {
                throw new IllegalArgumentException("找不到文本块。这通常是由于前面的修改改变了文件的字符偏移或内容，建议分步执行。");
            }
            if (content.lastIndexOf(finalOld) != firstIndex) {
                throw new IllegalArgumentException("文本块在当前状态下不唯一");
            }
            return content.substring(0, firstIndex) + finalNew + content.substring(firstIndex + finalOld.length());
        }
    }

    private Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : mountManager.getWorkDir();
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private Path resolveCommandWorkPath(Path workPath, String workdir) throws IOException {
        if (Assert.isEmpty(workdir) || ".".equals(workdir)) {
            return workPath;
        }
        return resolveSafePath(workPath, workdir, false);
    }

    /**
     * 统一的命令安全校验（替代原 validateDangerousCommand + bash 内联检查）
     *
     * @return null 表示校验通过；非 null 为错误消息
     */
    private String validateCommand(String command) {
        if (Assert.isEmpty(command)) {
            return "错误：command 不能为空。";
        }

        String pid = Utils.pid();
        String lowerCmd = command.toLowerCase();

        // 1. 自保护：禁止杀死当前 Java 进程
        String killPattern = "(?i).*(?:kill|pkill|killall)\\s+[\\s\\w]*\\b" + pid + "\\b.*";
        if (lowerCmd.matches(killPattern) ||
                lowerCmd.contains("pkill java") ||
                lowerCmd.contains("killall java")) {
            return "错误：检测到危险命令。严禁试图停止宿主进程 (PID: " + pid + ")。";
        }

        // 2. 系统破坏/自毁命令
        // exit: 仅拦截作为独立命令出现的 exit（行首/分号/管道/&&/|| 后），不拦截 echo 等参数中的 exit
        if (lowerCmd.matches("(?i)^exit\\b.*") ||
                lowerCmd.matches("(?i).*(?:;|\\|\\|?|&&)\\s*exit\\b.*") ||
                lowerCmd.matches("(?i).*rm\\s+.*-[rR].*f\\s+/.*") ||
                lowerCmd.matches("(?i).*(?:shutdown|reboot|init\\s+0|telinit).*") ||
                lowerCmd.matches("(?i).*(?:dd\\s+if=|mkfs|format\\s+[a-z]:).*") ||
                lowerCmd.matches("(?i).*:\\(\\)\\s*\\{|:.*\\|.*&.*\\}.*") ||  // fork bomb
                lowerCmd.matches("(?i).*(?:sysctl\\s+-w|modprobe|crontab).*") ||
                lowerCmd.matches("(?i).*(?:systemctl\\s+(?:stop|disable|mask|kill|reset-failed)).*") ||
                lowerCmd.matches("(?i).*\\b(?:nc|ncat|socat)\\b.*(?:-(?:e|c|l|p)\\s|/bin/|\\|\\s*sh).*") ||
                lowerCmd.matches("(?i).*(?:iptables|ufw|firewall-cmd).*") ||
                lowerCmd.matches("(?i).*(?:pip\\s+install|npm\\s+install|gem\\s+install).*\\s-[gG]\\b.*")) {
            return "错误：检测到高危指令，已拦截。";
        }

        // 3. 沙盒模式下的绝对路径检测
        if (sandboxMode) {
            // 检测类 Unix 绝对路径（排除 $ 开头的环境变量引用）
            if (command.matches("(?s).*(?<![\\$\\w\\-/])\\s/[a-zA-Z][\\w/].*") ||
                    command.matches("(?i).*[a-z]:[\\\\/].*")) {
                return "错误：沙盒模式下禁止在 bash 命令中使用绝对路径。请使用相对路径或逻辑路径（如 @pool）。";
            }

            // ~ 路径检测
            if (command.contains("~") && !allowUserHome) {
                return "错误：沙盒模式下禁止使用 ~ 路径（allowUserHome 已关闭）。";
            }
        }

        return null; // null 表示校验通过
    }

    private String formatCommandSnapshot(TerminalSessionManager.CommandSnapshot snapshot, String sourceTool) {
        StringBuilder sb = new StringBuilder();
        sb.append("Command Session\n");
        sb.append("source_tool: ").append(sourceTool).append('\n');
        sb.append("session_id: ").append(snapshot.sessionId()).append('\n');
        sb.append("status: ").append(snapshot.running() ? "running" : "completed").append('\n');
        if (snapshot.exitCode() != null) {
            sb.append("exit_code: ").append(snapshot.exitCode()).append('\n');
        }
        if (snapshot.timedOut()) {
            sb.append("hard_timeout: true\n");
        }
        if (snapshot.terminated()) {
            sb.append("terminated: true\n");
        }
        if (snapshot.terminateReason() != null) {
            sb.append("terminate_reason: ").append(snapshot.terminateReason()).append('\n');
        }
        sb.append("wall_time_ms: ").append(snapshot.wallTimeMs()).append('\n');
        sb.append("workdir: ").append(snapshot.workdir()).append('\n');
        sb.append("output_chars_total: ").append(snapshot.outputChars()).append('\n');
        sb.append("output_chars_returned: ").append(snapshot.returnedChars()).append('\n');
        sb.append("output_truncated: ").append(snapshot.outputTruncated()).append('\n');
        if (snapshot.running()) {
            sb.append("Process running with session ID: ").append(snapshot.sessionId()).append('\n');
            sb.append("Use bash_wait to continue waiting, bash_stdin to send input, or bash_stop to stop it.\n");
        }
        sb.append("Output:\n");
        if (Assert.isEmpty(snapshot.output())) {
            sb.append("(no new output)");
        } else {
            sb.append(snapshot.output());
        }
        return sb.toString();
    }

    private String preprocessUserHome(String pStr) {
        if (pStr == null) return null;

        // 支持 ~/ 或 ~ 转换为用户主目录
        if (pStr.equals("~") || pStr.startsWith("~/") || pStr.startsWith("~\\")) {
            String userHome = System.getProperty("user.home");
            if (pStr.length() == 1) {
                return userHome;
            } else {
                return Paths.get(userHome, pStr.substring(2)).toString();
            }
        }
        return pStr;
    }


    private Path resolveSafePath(Path workPath, String pStr, boolean writeMode) throws IOException {
        if (Assert.isEmpty(pStr) || ".".equals(pStr)) {
            return workPath;
        }

        if (pStr.startsWith("./")) {
            pStr = pStr.substring(2);
        }

        // 1. 如果是逻辑路径（@开头），走 mountManager 逻辑
        if (pStr.startsWith("@")) {
            Path target = mountManager.resolve(workPath, pStr);
            String alias = pStr.split("[/\\\\]")[0];
            MountDir mount = mountManager.getMount(alias);

            if (mount == null) {
                throw new SecurityException("权限拒绝：未知的挂载点 " + pStr);
            }

            if (writeMode && !mount.isWriteable()) {
                throw new SecurityException(
                        "权限拒绝：路径 " + pStr + " 属于只读挂载点，禁止写入。请将结果写入工作区的相对路径。");
            }

            // 符号链接防护：解析真实路径
            if (sandboxMode) {
                try {
                    Path realTarget = target.toRealPath();
                    Path realMountPath = mount.getRealPath().toRealPath();
                    if (!realTarget.startsWith(realMountPath)) {
                        throw new SecurityException("权限拒绝：符号链接越界（沙盒模式已开启）。");
                    }
                } catch (NoSuchFileException e) {
                    // 目标不存在时无法 toRealPath，允许通过（后续操作会自然失败）
                }

                String relative = pStr.substring(alias.length()).replaceFirst("^[/\\\\]", "");
                enforceSandboxFsPolicy(workPath, mount.getRealPath(), target, relative, writeMode);
            }

            return target;
        }

        // 2. 处理物理路径
        String pStr2 = preprocessUserHome(pStr);
        Path p = Paths.get(pStr2);
        Path target;

        if (p.isAbsolute()) {
            // 【沙盒模式】拦截绝对路径
            if (sandboxMode) {
                // allowUserHome=true 且原始输入以 ~ 开头 → 放行
                if (!(allowUserHome && pStr.startsWith("~"))) {
                    throw new SecurityException("权限拒绝：沙盒模式下禁止使用绝对路径。");
                }
            }
            target = p.normalize();
        } else {
            // 相对路径
            target = workPath.resolve(pStr2).normalize();
        }

        // 3. 越界检查（沙盒模式）
        if (sandboxMode) {
            boolean isUserHomeAccess = allowUserHome && pStr.startsWith("~");
            if (!isUserHomeAccess) {
                // 符号链接防护：先解析真实路径再判断
                try {
                    Path realTarget = target.toRealPath();
                    Path realWorkPath = workPath.toRealPath();
                    if (!realTarget.startsWith(realWorkPath)) {
                        throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                    }
                } catch (NoSuchFileException e) {
                    // 目标不存在：解析其父目录（通常存在）的真实路径来判断
                    Path parent = target.getParent();
                    if (parent != null) {
                        try {
                            Path realParent = parent.toRealPath();
                            Path realWorkPath = workPath.toRealPath();
                            if (!realParent.startsWith(realWorkPath)) {
                                throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                            }
                        } catch (NoSuchFileException e2) {
                            // 父目录也不存在，回退到字符串检查
                            if (!target.startsWith(workPath)) {
                                throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                            }
                        }
                    } else {
                        // 无父目录的极端情况，回退到字符串检查
                        if (!target.startsWith(workPath)) {
                            throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                        }
                    }
                }
            }

            String relativePath = target.startsWith(workPath) ? workPath.relativize(target).toString() : null;
            enforceSandboxFsPolicy(workPath, workPath, target, relativePath, writeMode);
        }

        return target;
    }

    private void enforceSandboxFsPolicy(Path workPath, Path rootPath, Path target, String relativePath, boolean writeMode) throws IOException {
        if (!sandboxMode || sandboxConfig == null || sandboxConfig.getFilesystem() == null) {
            return;
        }

        SandboxFsConfig fsConfig = sandboxConfig.getFilesystem();
        if (writeMode) {
            if (isMandatoryDenyRelativePath(relativePath)) {
                throw new SecurityException("权限拒绝：路径受保护，禁止写入。");
            }
            if (!isWriteAllowed(rootPath, target, fsConfig)) {
                throw new SecurityException("权限拒绝：路径不在可写白名单内。");
            }
            if (matchesAnyConfiguredPath(rootPath, target, fsConfig.getDenyWrite())) {
                throw new SecurityException("权限拒绝：路径命中写入拒绝规则。");
            }
        } else {
            if (isReadDenied(rootPath, target, fsConfig)) {
                throw new SecurityException("权限拒绝：路径命中读取拒绝规则。");
            }
        }
    }

    private boolean isSandboxReadDenied(Path workPath, Path target) {
        if (!sandboxMode || sandboxConfig == null || sandboxConfig.getFilesystem() == null) {
            return false;
        }
        return isReadDenied(workPath, target, sandboxConfig.getFilesystem());
    }

    private boolean isReadDenied(Path workPath, Path target, SandboxFsConfig fsConfig) {
        return matchesAnyConfiguredPath(workPath, target, fsConfig.getDenyRead())
                && !matchesAnyConfiguredPath(workPath, target, fsConfig.getAllowRead());
    }

    private boolean isWriteAllowed(Path rootPath, Path target, SandboxFsConfig fsConfig) {
        for (String allowPath : fsConfig.getAllowWrite()) {
            Path allow = normalizeConfiguredPath(rootPath, allowPath);
            if (target.startsWith(allow)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyConfiguredPath(Path workPath, Path target, List<String> paths) {
        for (String configuredPath : paths) {
            Path normalized = normalizeConfiguredPath(workPath, configuredPath);
            if (target.startsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    private Path normalizeConfiguredPath(Path rootPath, String path) {
        if (Assert.isEmpty(path) || ".".equals(path)) {
            return rootPath.normalize();
        }
        Path configured = Paths.get(path);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return rootPath.resolve(configured).normalize();
    }

    private boolean isMandatoryDenyRelativePath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        return SandboxFsConfig.isMandatoryDenyPath(relativePath.replace("\\", "/"));
    }

    private String formatDisplayPath(Path workPath, String inputPath, Path targetDir, Path file) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String prefix = inputPath.split("[/\\\\]")[0];
            return prefix + "/" + targetDir.relativize(file).toString().replace("\\", "/");
        }


        // 开放模式下，如果文件不在 workPath 内部，返回绝对路径字符串
        if (!sandboxMode && !file.startsWith(workPath)) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }

        try {
            return workPath.relativize(file).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }
    }

    private String translateCommandToEnv(String command, Map<String, String> envs) {
        String result = command;
        for (MountDir mount : mountManager.getMounts()) {
            if (mount.isEnabled()) {
                String alias = mount.getAlias(); // 例如 @pool1
                String envKey = alias.substring(1).toUpperCase(); // POOL1

                // 仅注入命令中实际使用的环境变量（减少污染）
                if (result.contains(alias)) {
                    envs.put(envKey, mount.getRealPath().toString());
                    String placeholder = getEnvPlaceholder(envKey);

                    // 精确替换：仅替换作为路径前缀出现的 @alias（后跟 / 或 \\ 或在行尾）
                    result = result.replaceAll(
                            java.util.regex.Pattern.quote(alias) + "(?=[/\\\\\\s]|$)",
                            java.util.regex.Matcher.quoteReplacement(placeholder)
                    );
                }
            }
        }

        // ~ 路径处理（统一所有 shell 模式）
        if (result.contains("~")) {
            if (sandboxMode && !allowUserHome) {
                throw new SecurityException("权限拒绝：沙盒模式下禁止使用 ~ 路径（allowUserHome 已关闭）。");
            }
            String userHome = System.getProperty("user.home").replace("\\", "/");
            result = result.replace("~/", userHome + "/")
                           .replace("~\\", userHome + "/");
            if (result.trim().equals("~")) {
                result = result.replace("~", userHome);
            }
        }

        return result;
    }

    private String getEnvPlaceholder(String envKey) {
        switch (this.shellMode) {
            case CMD:
                return "%" + envKey + "%";
            case POWERSHELL:
                return "$env:" + envKey;
            case UNIX_SHELL:
            default:
                return "$" + envKey;
        }
    }

    private void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden) throws IOException {
        if (depth >= maxDepth) return;
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> !isSandboxReadDenied(workPath, p))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().compareTo(b.getFileName());
                    }).collect(Collectors.toList());

            for (int i = 0; i < children.size(); i++) {
                Path child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                boolean isDir = Files.isDirectory(child);
                sb.append(indent).append(isLast ? "└── " : "├── ").append(child.getFileName()).append("\n");
                if (isDir)
                    generateTreeInternal(workPath, child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── [拒绝访问]\n");
        }
    }

    private String flatListLogic(Path workPath, Path target, String inputPath, boolean showHidden) throws IOException {
        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> !isSandboxReadDenied(workPath, p))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String displayPath = formatDisplayPath(workPath, inputPath, target, p);
                        return (isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : "");
                    }).sorted().collect(Collectors.toList());
            return lines.isEmpty() ? "(目录为空)" : String.join("\n", lines);
        }
    }


    private boolean isIgnored(Path workPath, Path path) {
        String name = path.getFileName().toString();
        if (ignoreDirs.contains(name)) return true;
        try {
            // 只有在 workPath 内部时才进行递归片段检查
            if (path.startsWith(workPath)) {
                Path relative = workPath.relativize(path);
                for (Path segment : relative) {
                    if (ignoreDirs.contains(segment.toString())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isNotTextFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        // 1. 基于已知二进制后缀的快速过滤
        if (fileName.endsWith(".class") || fileName.endsWith(".jar") ||
                fileName.endsWith(".exe")   || fileName.endsWith(".dll") ||
                fileName.endsWith(".so")    || fileName.endsWith(".pyc") ||
                fileName.endsWith(".png")   || fileName.endsWith(".jpg") ||
                fileName.endsWith(".gif")   || fileName.endsWith(".zip") ||
                fileName.endsWith(".gz")    || fileName.endsWith(".pdf")) {
            return true;
        }

        return false;
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

    public static class EditOp {
        @Param(value = "old_str", description = "待替换的唯一文本块。必须唯一且包含精确缩进。")
        public String oldStr;
        @Param(value = "new_str", description = "替换后的新内容")
        public String newStr;
        @Param(value = "replace_all", required = false, defaultValue = "false", description = "是否替换所有匹配项（仅当 old_str 全文唯一时执行替换）。")
        public Boolean replaceAll = false; // 赋默认值
    }
}
