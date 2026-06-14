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
import org.noear.solon.ai.sandbox.SandboxManager;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.SandboxViolationStore;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import com.github.difflib.patch.PatchFailedException;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.lang.Nullable;

/**
 * Claude Code 规范对齐的 CLI 基础执行才能
 *
 * @author noear
 * @since 3.9.1
 */
public class TerminalTalent extends AbsTalent {
    public static final String TOOL_WRITE = "write";
    public static final String TOOL_EDIT = "edit";

    public static final String PARAM_CONTENT = "content";
    public static final String PARAM_DIFF = "diff";


    static enum ShellMode {
        CMD, POWERSHELL, UNIX_SHELL
    }


    private final String shellCmd;
    private final String extension;
    private final ShellMode shellMode;
    private final TerminalSupport support;

    //沙盒模式：只能访问相对路径或逻辑路径；（否则为）开放模式：可以访问绝对路径
    private boolean sandboxEnabled = true;
    //允许访问用户主目录（~ 路径）。仅在 sandboxEnabled=true 时有意义；默认 true 保持向后兼容
    private boolean sandboxAllowUserHome = true;
    //OS 内核级沙盒限制：是否启用 Seatbelt/bwrap 等系统级强制隔离。
    //关闭后仅依赖 Java 层自保护 + 系统提示词软约束，可减少误伤（如构建工具被拦截）。
    //仅在 sandboxEnabled=true 时有意义；默认 false（轻量模式）
    private boolean sandboxSystemRestrict = false;
    private final MountManager mountManager; // 引入挂载管理器
    private @Nullable SandboxRuntimeConfig sandboxConfig;
    private final SandboxViolationStore violationStore = new SandboxViolationStore(Collections.emptyMap());

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

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    public void setSandboxEnabled(Boolean sandboxEnabled) {
        if (sandboxEnabled != null) {
            this.sandboxEnabled = sandboxEnabled;
        }
    }

    public void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        if (sandboxAllowUserHome != null) {
            this.sandboxAllowUserHome = sandboxAllowUserHome;
        }
    }

    public boolean isSandboxSystemRestrict() {
        return sandboxSystemRestrict;
    }

    public void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        if (sandboxSystemRestrict != null) {
            this.sandboxSystemRestrict = sandboxSystemRestrict;
        }
    }

    public boolean isBashAsyncEnabled() {
        return bashAsyncEnabled;
    }

    public void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        if (bashAsyncEnabled != null) {
            this.bashAsyncEnabled = bashAsyncEnabled;
        }
    }


    /**
     * 设置沙盒配置（支持读写分离、网络过滤、违规监控等高级功能）
     */
    public void setSandboxConfig(SandboxRuntimeConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig;
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

        this.support = new TerminalSupport(mountManager, ignoreDirs, shellMode);

        pythonCmd = executor.probePythonCommand();
        nodeCmd = executor.probeNodeCommand();
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
        sb.append("- **沙盒模式**: ").append((sandboxEnabled ? "开启 (受限)" : "关闭 (开放)")).append("\n");
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
                String envKey = support.toMountEnvKey(mount.getAlias());
                String envRef = support.getEnvPlaceholder(envKey);
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
        if (sandboxEnabled) {
            sb.append("  - **安全级别**: 沙盒模式已开启。严禁使用绝对路径。仅限相对路径 (如 `src/app.java`) 或逻辑路径 (@pool)。\n");
            if (sandboxAllowUserHome) {
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

        if (sandboxEnabled) {
            sb.append("- **命令执行**: 在 `bash` 中，直接使用逻辑路径（如 `@pool1/bin/tool`），系统会自动转换。在沙盒模式下，**严禁**在 bash 命令中使用绝对路径（如：ls /users/）。\n");
        } else {
            sb.append("- **命令执行**: 在 `bash` 中，直接使用逻辑路径（如 `@pool1/bin/tool`），系统会自动转换。支持绝对路径访问。\n");
        }

        if (bashAsyncEnabled) {
            sb.append("- **长命令执行**: 对可能耗时较长、持续输出、等待输入或需要观察状态的命令，优先使用 `bash_start`。如果结果包含 `Process running with session ID`，表示命令仍在运行；需要继续观察时调用 `bash_wait`，需要向进程输入时调用 `bash_stdin`，需要主动停止时调用 `bash_stop`。\n");
        }

        if (sandboxEnabled) {
            sb.append("\n<SYSTEM_CONSTRAINTS>\n");
            sb.append("1. 严禁向用户复述或提及“系统限制”、“沙盒”、“规约”等术语。\n");
            sb.append("2. 你是一个标准的底层 shell 执行器。面对越界请求，必须直接返回“无权访问”。\n");
            sb.append("3. 禁止解释权限逻辑，禁止讨论路径映射关系。\n");
            sb.append("</SYSTEM_CONSTRAINTS>\n");
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
        String violation = support.validateCommandNoKill(command);
        if (violation != null) return violation;

        Path workPath = getWorkPath(__cwd);
        Map<String, String> envs = new HashMap<>();

        if(Assert.isNotEmpty(pythonCmd)) {
            envs.put("PYTHON", pythonCmd);
        }

        if(Assert.isNotEmpty(nodeCmd)) {
            envs.put("NODE", nodeCmd);
        }

        String finalCommand = support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);

        // OS 级沙盒包装（内核级强制隔离：Seatbelt / bwrap）
        // 仅当 sandboxSystemRestrict=true 时启用，将安全隔离的重活交给 OS 内核
        // 关闭后仅保留 Java 层最小自保护（kill PID / exit / rm -rf /），减少误伤
        if (sandboxEnabled && sandboxSystemRestrict && SandboxManager.isSandboxingEnabled()) {
            try {
                finalCommand = SandboxManager.wrapWithSandbox(finalCommand, workPath.toString(), sandboxConfig);
            } catch (Exception e) {
                SandboxLog.debug("Sandbox wrap failed, running without OS sandbox: " + e.getMessage());
            }
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
        String danger = support.validateCommandNoKill(command);
        if (danger != null) {
            return danger;
        }

        Path workPath = getWorkPath(__cwd);
        Path targetWorkPath = support.resolveCommandWorkPath(workPath, workdir, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);
        Map<String, String> envs = new HashMap<>();

        if(Assert.isNotEmpty(pythonCmd)) {
            envs.put("PYTHON", pythonCmd);
        }
        if(Assert.isNotEmpty(nodeCmd)) {
            envs.put("NODE", nodeCmd);
        }

        String finalCommand = support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);

        // OS 级沙盒包装（内核级强制隔离：Seatbelt / bwrap）
        // 仅当 sandboxSystemRestrict=true 时启用，将安全隔离的重活交给 OS 内核
        // 关闭后仅保留 Java 层最小自保护（kill PID / exit / rm -rf /），减少误伤
        if (sandboxEnabled && sandboxSystemRestrict && SandboxManager.isSandboxingEnabled()) {
            try {
                finalCommand = SandboxManager.wrapWithSandbox(finalCommand, targetWorkPath.toString(), sandboxConfig);
            } catch (Exception e) {
                SandboxLog.debug("Sandbox wrap failed, running without OS sandbox: " + e.getMessage());
            }
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

        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);

        if (!Files.exists(target)) {
            return "错误：路径不存在";
        }

        if (Boolean.TRUE.equals(recursive)) {
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");
            support.generateTreeInternal(support.getSandboxPolicyRoot(workPath, path), target, 0, 3, "", sb, Boolean.TRUE.equals(showHidden), sandboxEnabled, sandboxConfig);
            return sb.toString();
        } else {
            return support.flatListLogic(workPath, support.getSandboxPolicyRoot(workPath, path), target, path, Boolean.TRUE.equals(showHidden), sandboxEnabled, sandboxConfig);
        }
    }

    // --- 3. 读取内容 ---
    @ToolMapping(name = "read", description = "读取文件内容。修改文件前先通过此工具确认最新的文本内容、缩进和换行符。支持大文件分页。支持逻辑路径（如 @pool）。优先尝试不限制读取（即尝试完整读取）")
    public String read(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "offset", required = false, defaultValue = "1", description = "开始读取的行号（默认从1开始索引）") Integer offset,
                       @Param(value = "limit", required = false, description = "需要读取的最大行数（默认不限制）。注意：单次读取受 128KB 物理长度保护，若触发截断，请根据输出提示调整 offset 分页读取。") Integer limit,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);

        Path target = support.resolveSafePath(workPath, filePath, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);
        if (!Files.exists(target)) {
            return "错误：文件不存在";
        }

        if (support.isNotTextFile(target)) {
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
                if (contentBuilder.length() + lineOutput.length() > TerminalSupport.MAX_CHARACTER_LIMIT) {
                    isByteTruncated = true;
                    // 边界：单行本身超过物理上限（如 minified JS/CSS、单行大 JSON）时 contentBuilder 仍为空，
                    // 直接 break 会导致无内容输出且 actualEndLine 不前进，分页提示 offset 与本次相同 → AI 重试死循环。
                    // 故输出该行的安全截断片段并让行号前进一行，保证分页可推进。
                    if (contentBuilder.length() == 0) {
                        String prefix = String.format("%6d | ", startLine0 + count + 1);
                        int slice = Math.max(0, Math.min(line.length(), TerminalSupport.MAX_CHARACTER_LIMIT - prefix.length() - 16));
                        contentBuilder.append(prefix)
                                .append(line, 0, slice)
                                .append("…(单行过长已截断)\n");
                        actualEndLine++;
                    }
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
        Path target = support.resolveSafePath(workPath, filePath, true, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return "文件成功写入: " + filePath;
    }


    @ToolMapping(
            name = "edit",
            description = "对文件应用 git diff 格式的补丁（Unified Diff）。支持单次补丁包含多个 @@ 块以实现多处修改，具有原子性：补丁完全应用成功才会写入，否则全部回滚。\n\n" +
                    "格式要求：\n" +
                    "1. diff 必须包含完整的 Unified Diff 文件头（--- 和 +++）以及一个或多个 @@ 块。\n" +
                    "2. 不要只提交 @@ 块。\n" +
                    "3. 上下文行必须来自文件原文，并尽量保持原始缩进、空格和内容一致。\n" +
                    "4. 建议每个 @@ 块在修改位置前后各保留 2-3 行上下文，以提高定位成功率。\n" +
                    "5. diff 头中的路径仅用于满足 Unified Diff 格式，实际应用目标以 file_path 参数为准。\n" +
                    "6. 支持 Markdown 代码块包裹；如使用代码块，代码块内必须是完整 diff 内容。\n\n" +
                    "示例格式：\n" +
                    "```diff\n" +
                    "--- a/Hello.java\n" +
                    "+++ b/Hello.java\n" +
                    "@@ -10,7 +10,7 @@\n" +
                    " 上下文行\n" +
                    "-要删除的行\n" +
                    "+新增的行\n" +
                    " 上下文行\n" +
                    "```"
    )
    public String edit(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "diff", description = "Unified Diff 格式的补丁内容（标准 git diff 格式），必须包含 ---/+++ 文件头、@@ 块和上下文行；支持单个补丁包含多个 @@ 块；支持 Markdown 代码块包裹。") String diff,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, filePath, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);
        // 第二次调用仅用于触发写权限校验（writeMode=true）：edit 会落盘写入，
        // 须确保目标不在只读挂载点 / 写入拒绝规则内；返回值无需使用。
        support.resolveSafePath(workPath, filePath, true, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);

        if (!Files.exists(target)) {
            return buildEditFailureMessage(filePath, "文件检查", "目标文件不存在", null);
        }

        if (diff == null || diff.trim().isEmpty()) {
            return buildEditFailureMessage(filePath, "参数校验", "diff 内容不能为空", "请提供完整 Unified Diff 补丁内容。");
        }

        String originalContent = new String(Files.readAllBytes(target), fileCharset);

        try {
            // 使用 java-diff-utils 解析并应用 Unified Diff 补丁
            String newContent = support.applyDiffLogic(originalContent, diff);

            // 内容无变化时直接返回
            if (newContent.equals(originalContent)) {
                return "提示：补丁应用后文件内容无变化。";
            }

            // 原子性写入（仅在补丁完全成功后执行）
            Files.write(target, newContent.getBytes(fileCharset));
            return "文件 " + filePath + " 成功应用补丁。";
        } catch (PatchFailedException e) {
            return buildEditFailureMessage(filePath, "补丁应用", "补丁中的某个 @@ 块无法匹配目标文件", e.getMessage());
        } catch (IllegalArgumentException e) {
            if (isPatchApplyFailure(e)) {
                return buildEditFailureMessage(filePath, "补丁应用", "补丁中的某个 @@ 块无法匹配目标文件", e.getMessage());
            }
            return buildEditFailureMessage(filePath, "diff 解析", normalizeErrorMessage(e), null);
        } catch (Exception e) {
            return buildEditFailureMessage(filePath, "补丁应用", normalizeErrorMessage(e), null);
        }
    }

    private String buildEditFailureMessage(String filePath, String stage, String reason, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("编辑失败：Unified Diff 补丁未能应用，文件未被修改。\n\n");
        sb.append("目标文件：").append(filePath).append('\n');
        sb.append("失败阶段：").append(stage).append('\n');
        sb.append("失败原因：").append(reason == null || reason.isEmpty() ? "未知错误" : reason).append('\n');

        if (detail != null && !detail.isEmpty()) {
            sb.append("诊断信息：").append(detail).append('\n');
        }

        sb.append("\n修复建议：\n");
        sb.append("1. 重新读取目标文件的最新内容；\n");
        sb.append("2. 从原文中逐字复制上下文行，保持缩进、空格和空行一致；\n");
        sb.append("3. 每个 @@ 块建议保留修改位置前后 2-3 行真实上下文；\n");
        sb.append("4. 重新提交完整 Unified Diff，必须包含 ---、+++ 和 @@ 块；\n");
        sb.append("5. 不要提交普通代码片段、JSON 或 search/replace 格式。");
        return sb.toString();
    }

    private String normalizeErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isEmpty()) {
            return "补丁格式不正确或无法应用";
        }

        return e.getMessage();
    }

    private boolean isPatchApplyFailure(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }

        String message = e.getMessage();
        // java-diff-utils 的 fuzzy 应用在 hunk 无法定位时，部分版本会抛出
        // IllegalArgumentException（如 fromIndex > toIndex），但语义上仍是补丁应用失败。
        return message.contains("fromIndex") ||
                message.contains("toIndex") ||
                message.contains("Incorrect Chunk") ||
                message.contains("could not apply");
    }

    // --- 5. 搜索工具 ---
    @ToolMapping(name = "grep", description = "递归搜索内容。返回 '路径:行号:内容'。在不确定文件位置时先执行搜索。支持逻辑路径（如 @pool）。pattern 支持正则表达式匹配。")
    public String grep(@Param(value = "pattern", description = "搜索内容，支持正则表达式匹配") String pattern,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       @Param(value = "include", required = false, description = "要包含的文件模式（如 \"*.js\"、\"*.{ts,tsx}\"）") String include,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);

        // 预编译正则，若语法无效则回退到 contains 匹配
        final Pattern finalPattern;
        Pattern compiled = null;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException ignored) {
            // 正则语法错误，回退到 contains 匹配
        } finally {
            finalPattern = compiled;
        }

        // 构建 include 的 PathMatcher（如果提供了 include 参数）
        final PathMatcher includeMatcher = buildIncludeMatcher(include);

        StringBuilder sb = new StringBuilder();
        Path policyRoot = support.getSandboxPolicyRoot(workPath, path);

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, dir) || support.isIgnored(target, dir) || support.isSandboxBoundaryDenied(policyRoot, dir, sandboxEnabled) || support.isSandboxReadDenied(policyRoot, dir, sandboxEnabled, sandboxConfig)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, file) || support.isIgnored(target, file) || support.isSandboxBoundaryDenied(policyRoot, file, sandboxEnabled) || support.isSandboxReadDenied(policyRoot, file, sandboxEnabled, sandboxConfig)) {
                    return FileVisitResult.CONTINUE;
                }

                // include 过滤：如果指定了文件模式，仅匹配符合模式的文件
                if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }

                if (attrs.size() > 10 * 1024 * 1024 || support.isNotTextFile(file)) {
                    return FileVisitResult.CONTINUE;
                }

                try (BufferedReader reader = Files.newBufferedReader(file, fileCharset)) {
                    int lineNum = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineNum++;
                        if (finalPattern != null ? finalPattern.matcher(line).find() : line.contains(pattern)) {
                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 1000) {
                                trimmedLine = trimmedLine.substring(0, 1000) + "...(line truncated)";
                            }

                            String displayPath = support.formatDisplayPath(workPath, path, target, file, sandboxEnabled);
                            sb.append(displayPath).append(":").append(lineNum).append(": ").append(trimmedLine).append("\n");

                            // 发现匹配后立即检查长度，防止 StringBuilder 过载
                            if (sb.length() > TerminalSupport.MAX_CHARACTER_LIMIT) {
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

        if (sb.length() >= TerminalSupport.MAX_CHARACTER_LIMIT) {
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
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);

        String fixedPattern = pattern.replace("\\", "/");
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fixedPattern);

        List<String> results = new ArrayList<>();
        Path policyRoot = support.getSandboxPolicyRoot(workPath, path);

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, dir) || support.isIgnored(target, dir) || support.isSandboxBoundaryDenied(policyRoot, dir, sandboxEnabled) || support.isSandboxReadDenied(policyRoot, dir, sandboxEnabled, sandboxConfig)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, file) || support.isIgnored(target, file) || support.isSandboxBoundaryDenied(policyRoot, file, sandboxEnabled) || support.isSandboxReadDenied(policyRoot, file, sandboxEnabled, sandboxConfig)) {
                    return FileVisitResult.CONTINUE;
                }

                if(matcher.matches(target.relativize(file)) || matcher.matches(file)) {
                    results.add("[FILE] " + support.formatDisplayPath(workPath, path, target, file, sandboxEnabled));
                }

                return results.size() >= 500 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (results.isEmpty()) return "未找到匹配文件。";
        Collections.sort(results);
        return String.join("\n", results);
    }

    // --- 内部逻辑逻辑 ---

    /**
     * 构建 include 参数对应的 PathMatcher。
     * 支持简单的 glob 模式，如 "*.java", "*.{ts,tsx}" 等。
     * 仅匹配文件名部分（非路径）。
     */
    private PathMatcher buildIncludeMatcher(String include) {
        if (include == null || include.isEmpty()) {
            return null;
        }
        // Java 的 glob 语法天然支持 {ts,tsx} 这种模式
        return FileSystems.getDefault().getPathMatcher("glob:" + include.replace("\\", "/"));
    }

    private Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : mountManager.getWorkDir();
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
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

    // ========== 沙盒相关桥接方法（供测试反射调用） ==========

    boolean containsUserHomePath(String command) {
        return support.containsUserHomePath(command);
    }

    public String validateCommand(String command) {
        // 先做基础安全校验（kill 保护等）
        String violation = support.validateCommandNoKill(command);
        if (violation != null) {
            return violation;
        }
        // sandboxAllowUserHome=false 时，阻止 ~ 路径
        if (!sandboxAllowUserHome && command.contains("~")) {
            // 简单检查：裸 ~ 或 ~/xxx，排除引号内的纯文本 ~
            String expanded = command.replaceAll("\"[^\"]*\"", "").replaceAll("'[^']*'", "");
            if (expanded.contains("~")) {
                return "错误：sandboxAllowUserHome 已禁用，不允许使用 ~ 路径。";
            }
        }
        return null;
    }

    String translateCommandToEnv(String command, java.util.Map<String, String> envs) {
        return support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);
    }

}
