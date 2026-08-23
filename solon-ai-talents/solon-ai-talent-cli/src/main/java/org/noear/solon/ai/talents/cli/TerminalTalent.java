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
import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.concurrent.locks.ReentrantLock;
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
    public static final String PARAM_EDITS = "edits";


    private final ShellCommandFactory shellCommandFactory;
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
    /**
     * 单次读取/搜索的最大物理长度限制（字符数）。
     * 默认 128KB (128 * 1024)。
     */
    private int maxCharacterLimit = 128 * 1024;
    private final MountManager mountManager; // 引入挂载管理器
    private @Nullable SandboxRuntimeConfig sandboxConfig;
    private final ReentrantLock sandboxInitLock = new ReentrantLock();
    private final SandboxViolationStore violationStore = new SandboxViolationStore(Collections.emptyMap());

    /** 运行时探测失败后的重试间隔：失败结果在窗口期内直接返回 null，避免每次对话都 fork 探测（最长等 2s 超时） */
    private static final long PROBE_RETRY_INTERVAL_MS = 60_000;

    private volatile String pythonCmd;
    private volatile String nodeCmd;
    /** 上次 python 探测失败时间（0 表示无失败记录） */
    private volatile long pythonProbeFailedAt;
    /** 上次 node 探测失败时间（0 表示无失败记录） */
    private volatile long nodeProbeFailedAt;

    protected Charset fileCharset = StandardCharsets.UTF_8;
    protected final ProcessExecutor executor = new ProcessExecutor();
    protected final TerminalSessionManager bashSessionManager;
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

    public int getMaxCharacterLimit() {
        return maxCharacterLimit;
    }

    public void setMaxCharacterLimit(int maxCharacterLimit) {
        this.maxCharacterLimit = maxCharacterLimit;
        if (support != null) {
            support.setMaxCharacterLimit(maxCharacterLimit);
        }
    }


    /**
     * 设置沙盒配置（支持读写分离、网络过滤、违规监控等高级功能）
     */
    public void setSandboxConfig(SandboxRuntimeConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig;
    }

    /**
     * 文件工具层（read/write/ls 等）的用户级路径策略。
     *
     * <p>恒返回 null：文件系统的可访问范围只由挂载生成（唯一正向来源），
     * 用户不再通过 FilesystemConfig 叠加相对路径黑白名单。文件工具层的边界
     * 完全由 resolveSafePath 的物理判定保底（工作区/挂载点/symlink 越界拦截），
     * 敏感文件由 mandatoryDeny 兜底。需要放行新区域时，请添加挂载配置。
     */
    private FilesystemConfig fs() {
        return null;
    }

    /**
     * 延迟初始化 SandboxManager。在 bash()/bashStart() 执行前自动调用，
     * 确保 Solon 配置注入完毕后才初始化，避免时序问题导致的单例锁定。
     *
     * <p>注意：文件系统路径白名单是动态构建的（每次 bash 调用时从当前挂载点重建），
     * 因此 init 时传入的 sandboxConfig 中的 filesystem 字段会被 buildDynamicCustomConfig()
     * 返回的动态配置覆盖，确保挂载点增删变化后沙箱边界实时生效。
     */
    private void ensureSandboxInitialized() {
        if (sandboxSystemRestrict && sandboxEnabled && !SandboxManager.isSandboxingEnabled()) {
            sandboxInitLock.lock();
            try {
                if (!SandboxManager.isSandboxingEnabled()) {
                    SandboxRuntimeConfig cfg = sandboxConfig != null
                            ? sandboxConfig
                            : buildDefaultSandboxConfig();
                    SandboxManager.initialize(cfg, null);
                }
            } catch (Exception e) {
                SandboxLog.debug("Auto sandbox init failed, running without OS sandbox: " + e.getMessage());
            } finally {
                sandboxInitLock.unlock();
            }
        }
    }

    /**
     * 构建动态沙箱配置：合并用户配置的 sandboxConfig（网络、seccomp 等静态部分）
     * 与当前挂载点的文件系统路径白名单（动态部分）。
     *
     * <p>文件系统路径每次从 mountManager 实时获取，确保挂载点增删变化后沙箱边界实时生效。
     * 返回值可作为 SandboxManager.wrapWithSandbox() 的 customConfig 参数传入。
     */
    private SandboxRuntimeConfig buildDynamicCustomConfig() {
        // 1) 从 mountManager 构建当前最新的文件系统白名单
        FilesystemConfig dynamicFs = buildDynamicFilesystemConfig();

        // 2) 文件系统可访问范围只由挂载生成（唯一正向来源）：用户配置的 filesystem 字段
        //    一律忽略，filesystem 始终使用动态挂载点白名单。用户其他配置（网络、seccomp 等）
        //    仍然保留。需要放行新区域时，请添加挂载配置。
        if (sandboxConfig != null) {
            return new SandboxRuntimeConfig(
                    sandboxConfig.getNetwork(),
                    dynamicFs,
                    sandboxConfig.getIgnoreViolations(),
                    sandboxConfig.getEnableWeakerNestedSandbox(),
                    sandboxConfig.getEnableWeakerNetworkIsolation(),
                    sandboxConfig.getAllowAppleEvents(),
                    sandboxConfig.getRipgrep(),
                    sandboxConfig.getMandatoryDenySearchDepth(),
                    sandboxConfig.getAllowPty(),
                    sandboxConfig.getSeccomp(),
                    sandboxConfig.getBwrapPath(),
                    sandboxConfig.getSocatPath(),
                    sandboxConfig.getWindows()
            );
        }

        // 3) 无用户配置，返回纯动态配置
        return new SandboxRuntimeConfig(
                null, dynamicFs, null,
                null, null, null, null,
                null, null, null, null, null, null
        );
    }

    /**
     * 基于当前挂载点构建动态文件系统配置。
     * 每次调用都会从 mountManager 实时读取最新挂载状态。
     */
    private FilesystemConfig buildDynamicFilesystemConfig() {
        List<String> allowWrite = new ArrayList<>();
        List<String> allowRead = new ArrayList<>();

        // 1) 工作区目录：允许读写（startsWith 匹配覆盖所有子路径）
        String workDir = mountManager.getWorkDir();
        if (workDir != null) {
            allowWrite.add(workDir);
            allowRead.add(workDir);
        }

        // 2) 所有挂载点：按可写性加入对应列表（无需 /** 后缀，startsWith 匹配覆盖子路径）
        for (MountDir mount : mountManager.getMounts()) {
            if (mount.isEnabled()) {
                Path realPath = mount.getRealPath();
                if (realPath != null) {
                    String pathStr = realPath.toString();
                    allowRead.add(pathStr);
                    if (mount.isWriteable()) {
                        allowWrite.add(pathStr);
                    }
                }
            }
        }

        // 3) 用户主目录：当启用 sandboxAllowUserHome 时加入读写白名单
        //    解决 agent-browser（npx ~/.npm/_npx）、npm 缓存（~/.npm/_cacache）
        //    等工具需要在用户主目录下读写的问题。
        if (sandboxAllowUserHome) {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                allowRead.add(userHome);
                allowWrite.add(userHome);
            }
        }

        return new FilesystemConfig(
                null,                           // denyRead
                allowRead.isEmpty() ? null : allowRead,
                allowWrite.isEmpty() ? null : allowWrite,
                null,                           // denyWrite
                true                            // allowGitConfig — 构建工具（Maven git-commit-id-plugin、Gradle git 插件等）需要读取 .git/config
        );
    }

    /**
     * 构建默认沙箱配置：复用动态文件系统白名单，让沙箱基于实际边界生效。
     * 当用户未显式提供 sandboxConfig 时自动启用。
     */
    private SandboxRuntimeConfig buildDefaultSandboxConfig() {
        // 复用 buildDynamicFilesystemConfig() 获取当前挂载点的实时路径白名单
        // 避免两份相同逻辑的维护成本
        FilesystemConfig fsConfig = buildDynamicFilesystemConfig();

        // NetworkConfig：保持网络不受限（allowedDomains=null），
        // 但允许 Unix domain socket（Gradle daemon 等构建工具需要 IPC）
        // 和本地端口绑定（构建服务器、测试框架需要绑定 localhost）
        NetworkConfig networkConfig = new NetworkConfig(
                null,   // allowedDomains (null = 不限制网络)
                null,   // deniedDomains
                null,   // allowUnixSockets (特定 Unix socket 路径白名单)
                true,   // allowAllUnixSockets (Gradle daemon IPC 需要)
                true,   // allowLocalBinding (构建工具绑定 localhost 端口)
                null,   // allowMachLookup
                null,   // httpProxyPort
                null,   // socksProxyPort
                null,   // mitmProxy
                null,   // parentProxy
                null,   // tlsTerminate
                null    // filterRequest
        );

        return new SandboxRuntimeConfig(
                networkConfig,
                fsConfig,
                null,                           // ignoreViolations
                null,                           // enableWeakerNestedSandbox (保持默认，用户可按需配置)
                null,                           // enableWeakerNetworkIsolation
                null,                           // allowAppleEvents
                null,                           // ripgrep
                null,                           // mandatoryDenySearchDepth
                true,                           // allowPty (测试框架交互需要伪终端)
                null,                           // seccomp
                null,                           // bwrapPath
                null,                           // socatPath
                null                            // windows
        );
    }

    /**
     * 获取违规存储（用于查询 OS 级拦截事件）
     */
    public SandboxViolationStore getViolationStore() {
        return violationStore;
    }

    public TerminalTalent(MountManager mountManager) {
        this(mountManager, ShellCommandFactory.detect());
    }

    /**
     * 显式指定 shell 方案的构造器（主要给测试用：引导词需要按 CMD / POWERSHELL / UNIX_SHELL
     * 三种方言分别验证，不能只依赖当前宿主机的探测结果）。
     */
    TerminalTalent(MountManager mountManager, ShellCommandFactory shellCommandFactory) {
        if (shellCommandFactory == null) {
            throw new IllegalArgumentException("shellCommandFactory is required");
        }
        this.mountManager = mountManager;

        this.shellCommandFactory = shellCommandFactory;
        this.shellMode = shellCommandFactory.getShellMode();
        this.bashSessionManager = new TerminalSessionManager(shellCommandFactory);

        this.support = new TerminalSupport(mountManager, ignoreDirs, shellMode);
        this.support.setMaxCharacterLimit(this.maxCharacterLimit);
        // python/node 探测延迟到首次使用时（惰性），
        // 确保用户在运行期间新安装的运行时也能被识别（配合实时 PATH 注入）
    }

    public ProcessExecutor getExecutor() {
        return executor;
    }

    /**
     * 惰性探测 Python 命令（线程安全）。
     *
     * <p>未找到时进入失败时间窗缓存（{@link #PROBE_RETRY_INTERVAL_MS}）：窗口期内
     * 直接返回 {@code null}，避免每次对话都 fork 探测进程（单次最长等 2s 超时）；
     * 窗口过期后重试，以便运行期间新安装的运行时能被自动识别。</p>
     */
    private String pythonCmd() {
        String cmd = pythonCmd;
        if (cmd == null) {
            if (System.currentTimeMillis() - pythonProbeFailedAt < PROBE_RETRY_INTERVAL_MS) {
                return null;
            }
            synchronized (this) {
                cmd = pythonCmd;
                if (cmd == null) {
                    cmd = executor.probePythonCommand();
                    if (cmd != null) {
                        pythonCmd = cmd;
                        pythonProbeFailedAt = 0;
                    } else {
                        pythonProbeFailedAt = System.currentTimeMillis();
                    }
                }
            }
        }
        return cmd;
    }

    /**
     * 惰性探测 Node 命令（线程安全）。失败时间窗缓存策略同 {@link #pythonCmd()}。
     */
    private String nodeCmd() {
        String cmd = nodeCmd;
        if (cmd == null) {
            if (System.currentTimeMillis() - nodeProbeFailedAt < PROBE_RETRY_INTERVAL_MS) {
                return null;
            }
            synchronized (this) {
                cmd = nodeCmd;
                if (cmd == null) {
                    cmd = executor.probeNodeCommand();
                    if (cmd != null) {
                        nodeCmd = cmd;
                        nodeProbeFailedAt = 0;
                    } else {
                        nodeProbeFailedAt = System.currentTimeMillis();
                    }
                }
            }
        }
        return cmd;
    }

    @Override
    public String description() {
        return "提供终端交互、文件发现、分页读取、全文搜索及精准编辑能力。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        // 当前 shell 是否为 Windows 系（CMD / PowerShell）。引导词中所有涉及
        // 命令方言、进程管理、路径语义的表述都必须按此分支给出，
        // 否则会向模型泄漏错误的平台先验（如在 Windows 上建议 pkill / 2>/dev/null）。
        boolean windowsShell = shellCommandFactory.isWindowsShell();

        sb.append("## Terminal 环境状态\n");
        sb.append("- **沙盒模式**: ").append((sandboxEnabled ? "开启 (受限)" : "关闭 (开放)")).append("\n");
        sb.append("- **运行环境**: ").append(System.getProperty("os.name"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- **终端类型**: ").append(shellMode).append(" — ").append(shellDialectSummary()).append("\n");


        // 在 getInstruction 增加以下逻辑
        sb.append("- **自我保护机制**:\n");
        sb.append("  - 你的所有指令都在 Java 进程 (PID: ").append(Utils.pid()).append(") 的子 shell 中运行。\n");
        sb.append("  - 杀死该 PID 或其父进程会导致你立即停止工作并丢失所有上下文。\n");
        if (windowsShell) {
            sb.append("  - 严禁执行 `taskkill /IM java.exe`、`Stop-Process -Name java`。严禁用 `taskkill /PID <PID>` 或 `Stop-Process -Id <PID>` 指向任意 PID，除非你先用 `Get-Process -Id <PID>`（或 `tasklist /FI \"PID eq <PID>\"`）确认该进程与当前 Java 进程无关。\n");
            sb.append("  - 建议：若需清理任务，只终止你自己启动的那个子进程（按其已知 PID 执行 `Stop-Process -Id <子PID>`），不要按进程名批量终止。\n");
        } else {
            sb.append("  - 严禁执行 `pkill java`, `killall java`。严禁执行 `kill -9` 任何数字，除非你先执行了 `ps` 明确该 PID 与当前 Java 进程无关。\n");
            sb.append("  - 建议：若需清理任务，请使用 `pkill -P [PID]` 仅停止子进程。\n");
        }

        sb.append("- **严禁指令**:\n");
        sb.append("  - 严禁执行 `exit`。如果你需要结束脚本，请让脚本自然执行完毕。\n");
        if (windowsShell) {
            sb.append("  - 严禁对盘符根目录（如 `C:\\`）或系统目录（如 `C:\\Windows`、`C:\\Program Files`、`C:\\Users`）执行任何删除操作。\n");
        } else {
            sb.append("  - 严禁执行任何针对根目录 `/` 或系统目录（如 `/etc`, `/usr`）的删除操作。\n");
        }
        sb.append("  - 严禁执行任何可能改变宿主系统状态的命令（如修改网络配置、安装系统驱动等）。\n");

        sb.append("- **执行环境**: \n");
        String pyCmd = pythonCmd();
        String ndCmd = nodeCmd();
        if(Assert.isNotEmpty(pyCmd)) {
            // 占位符必须按 shell 方言给出：CMD 为 %PYTHON%、PowerShell 为 $env:PYTHON、Unix 为 $PYTHON
            sb.append("  - Python 命令: `").append(pyCmd).append("` (系统已预置变量 `")
                    .append(support.getEnvPlaceholder("PYTHON")).append("`)\n");
        }
        if(Assert.isNotEmpty(ndCmd)) {
            sb.append("  - Node.js 命令: `").append(ndCmd).append("` (系统已预置变量 `")
                    .append(support.getEnvPlaceholder("NODE")).append("`)\n");
        }

        // 动态判断是否有可写挂载点
        boolean hasWriteableMount = mountManager.getMounts().stream()
                .anyMatch(m->m.isEnabled() && m.isWriteable());

        boolean hasMount = mountManager.getMounts().stream()
                .anyMatch(m->m.isEnabled());

        sb.append("- **路径规则**: \n");
        sb.append("  - **工作区（默认作用域）**: 你的主目录，支持读写。所有文件查找（ls/glob/grep/read）与路径解析默认都以工作区为根，使用相对路径访问（如 `src/app.java`）。\n");

        if(hasMount) {
            sb.append("  - **挂载点（仅按需访问）**: 以 `@` 开头的逻辑路径（如 `@pool1/bin/tool/`），对应一个真实的物理目录。见下方挂载点清单。**仅当用户的提示词中明确提及了具体的挂载点名时**（如 `@global-skills`、`@workspace-agents`），才去对应挂载点下查找。\n");
        }

        // 挂载点清单表格
        if(hasMount) {
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
        }


        if (sandboxEnabled) {
            if (hasMount) {
                sb.append("  - **安全级别**: 沙盒模式已开启。严禁使用绝对路径。仅限相对路径 (如 `src/app.java`) 或逻辑路径 (`@pool1/src/app.java`)。\n");
            } else {
                sb.append("  - **安全级别**: 沙盒模式已开启。严禁使用绝对路径。仅限相对路径 (如 `src/app.java`)。\n");
            }

            if (sandboxAllowUserHome) {
                sb.append("  - `~` 路径可用（如 `~/Documents`）。\n");
            } else {
                sb.append("  - `~` 路径已禁用。\n");
            }
        } else {
            sb.append("  - **安全级别**: 开放模式。支持绝对路径、相对路径及逻辑路径。\n");
        }

        sb.append("## 执行规约\n");

        if(hasMount) {
            if (hasWriteableMount) {
                sb.append("- **挂载隔离**: 逻辑路径（以 @ 开头）默认只读。仅当挂载点清单中 `writeable=\"true\"` 时，才允许写入操作。\n");
            } else {
                sb.append("- **挂载隔离**: 逻辑路径（以 @ 开头）均为只读，所有写入操作使用相对路径。\n");
            }
        }

        if (sandboxEnabled) {
            if (hasMount) {
                sb.append("- **命令执行**: 在 `bash` 中，直接使用逻辑路径（如 `cd @pool1/bin/tool/`），系统会自动转换。在沙盒模式下，**严禁**在 bash 命令中使用绝对路径（如：`ls /users/`）。\n");
            } else {
                sb.append("- **命令执行**: 在沙盒模式下，**严禁**在 bash 命令中使用绝对路径（如：ls /users/）。\n");
            }
        } else {
            if (hasMount) {
                sb.append("- **命令执行**: 在 `bash` 中，直接使用逻辑路径（如 `cd @pool1/bin/tool/`），系统会自动转换。也支持绝对路径访问。\n");
            } else {
                sb.append("- **命令执行**: 在 `bash` 中支持绝对路径访问。\n");
            }
        }

        // 工具优先规约必须对三种 shell 都输出：`bash` 这个工具名本身就是强先验，
        // 模型会习惯性用 cat/ls/find/grep/sed -i 代劳 read/ls/glob/grep/edit，
        // 从而绕开工具层的编码处理、行号定位、忽略目录、分页截断与写入原子回滚。
        sb.append("- **工具优先（先选工具，再考虑命令）**: 读文件、列目录、查找文件、全文检索、写文件与改文件，一律用 `read` / `ls` / `glob` / `grep` / `")
                .append(TOOL_WRITE).append("` / `").append(TOOL_EDIT)
                .append("` 工具，不要用 shell 命令代劳（如 ").append(fileShellCommandExamples()).append("）")
                .append("：工具层已统一处理编码与 BOM、行号定位、忽略无关目录（node_modules/target/.git 等）、大文件分页截断与写入原子回滚，命令行做不到这些。")
                .append("`bash` 用于真正需要执行的场景：构建、测试、git、包管理、脚本运行，以及工具无法覆盖的管道与统计。\n");

        if (bashAsyncEnabled) {
            sb.append("- **长命令执行**: 对可能耗时较长、持续输出、等待输入或需要观察状态的命令，优先使用 `bash_start`。如果结果包含 `Process running with session ID`，表示命令仍在运行；需要继续观察时调用 `bash_wait`，需要向进程输入时调用 `bash_stdin`，需要主动停止时调用 `bash_stop`。\n");
        }

        // 参数示例必须用当前 shell 真存在的命令：在 Windows 上举 `python3` / `cat` 会被模型当作可用命令而直接照拄
        sb.append("- **参数与编码**: 含空格/引号等特殊字符的参数务必用引号包裹（如 `")
                .append(Assert.isNotEmpty(pyCmd) ? pyCmd : (windowsShell ? "python" : "python3"))
                .append(" a.py \"hello world\"`、`").append(readFileCommandExample()).append("`）。");
        // 编码说明只在 Windows 下成立：类 Unix 没有代码页概念（OutputDecoder 也不做代码页兼底），
        // 也不存在 chcp，向 Linux/macOS 上的模型描述这些只会注入错误的平台先验
        if (windowsShell) {
            sb.append("输出编码由系统自动识别（UTF-8 优先，Windows 遗留代码页兜底），无需自行加 chcp 或设置编码环境变量。\n");
        } else {
            sb.append("输出按 UTF-8 解码，无需额外设置编码环境变量。\n");
        }

        appendShellDialectRules(sb);

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
     * 当前 shell 的一句话定性说明，跟在「终端类型」后面。
     *
     * <p>只给出 {@code POWERSHELL} / {@code CMD} 这样的枚举名不足以纠偏：`bash` 这个工具名
     * 本身就是极强的先验，模型会默认自己在 POSIX 环境里。这里显式点明「不是 bash/sh」。</p>
     */
    private String shellDialectSummary() {
        if (shellMode == ShellMode.POWERSHELL) {
            return (shellCommandFactory.isPowerShellCore() ? "PowerShell 7+ (pwsh)" : "Windows PowerShell")
                    + "，**不是** bash/sh，POSIX 命令与语法一律不可用";
        }
        if (shellMode == ShellMode.CMD) {
            return "Windows cmd.exe 批处理，**不是** bash/sh，POSIX 命令与语法一律不可用";
        }
        return shellCommandFactory.getShellCmd() + "（POSIX shell）";
    }

    /**
     * 当前 shell 下「抢了 read / ls / glob / grep / write / edit 活」的典型命令，用作工具优先规约的负面示例。
     *
     * <p>必须按方言给出：在 CMD 下举 {@code cat} / {@code sed -i} 会被模型当作本机可用命令，
     * 反而泄漏错误的平台先验。</p>
     */
    private String fileShellCommandExamples() {
        if (shellMode == ShellMode.POWERSHELL) {
            return "`Get-Content`、`Get-ChildItem`、`Select-String`、`Set-Content`、`>`";
        }
        if (shellMode == ShellMode.CMD) {
            return "`type`、`dir`、`findstr`、`echo >`";
        }
        return "`cat`、`ls`、`find`、`grep`、`sed -i`、`echo >`";
    }

    /**
     * 「查看文件内容」在当前 shell 下的可用写法，用于参数引号示例。
     */
    private String readFileCommandExample() {
        if (shellMode == ShellMode.POWERSHELL) {
            return "Get-Content \"my file.txt\"";
        }
        if (shellMode == ShellMode.CMD) {
            return "type \"my file.txt\"";
        }
        return "cat \"my file.txt\"";
    }

    /**
     * 追加当前 shell 的方言规则。
     *
     * <p>PowerShell / CMD 分支以「负面清单 + 等价写法」成对给出：只说「不要用 Unix 语法」
     * 模型无法自行推导替代写法，仍会退回 `2>/dev/null`、`head` 这类习惯用法。</p>
     */
    private void appendShellDialectRules(StringBuilder sb) {
        if (shellMode == ShellMode.POWERSHELL) {
            boolean core = shellCommandFactory.isPowerShellCore();
            sb.append("- **PowerShell 方言（必须遵守）**: 当前 shell 是 PowerShell，以下 Unix 写法在此**不存在**，用后必报错，须按右侧改写：\n");
            sb.append("  - 丢弃错误输出：`2>/dev/null` → `2>$null`；丢弃全部输出：`| Out-Null`。\n");
            if (core) {
                sb.append("  - 命令串联：`&&` / `||` 可用（PowerShell 7+），`;` 也可用；需要「前一步失败就停」时请用 `&&` 而不是 `;`。\n");
            } else {
                sb.append("  - 命令串联：`&&` / `||` → `;`（Windows PowerShell 5.1 不支持 `&&`、`||`）。\n");
                // `;` 不等于 `&&`：换写后丢的是「失败即停」语义，而整条命令的退出码只反映最后一步，
                // 前面的编译/测试失败会被后面一步的成功掩盖（模型会据此误判为构建通过）
                sb.append("    - 注意 `;` 不等于 `&&`：前一步失败不会中断后续步骤，且返回的 `[exit_code]` 只反映最后一步。")
                        .append("多步骤构建/测试请拆成多次 `bash` 调用，或写 `cmd1; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; cmd2`。\n");
            }
            sb.append("  - 取前/后 N 行：`head -n N` / `tail -n N` → `Select-Object -First N` / `-Last N`。\n");
            sb.append("  - 查看文件：`cat` → `Get-Content`；文本检索：`grep` → `Select-String`；查找文件：`find` → `Get-ChildItem -Recurse -Filter`。\n");
            sb.append("  - 系统信息：`uname -a`、`cat /etc/os-release` → `Get-ComputerInfo`、`$PSVersionTable`、`[System.Environment]::OSVersion`。\n");
            sb.append("  - 其它：`pwd` → `Get-Location`；`which x` → `Get-Command x`；`export A=B` → `$env:A='B'`；读环境变量用 `$env:NAME`（不是 `$NAME`）。\n");
            appendPowerShellAliasTrapRule(sb, core);
            appendMergedStderrRule(sb);
            sb.append("  - 不存在任何 Unix 路径：`/dev/null`、`/etc`、`/usr`、`/tmp` 均无效；路径分隔符为 `\\`，临时目录用 `$env:TEMP`。\n");
            appendEncodingRules(sb, core);
            sb.append("  - 上述方言与编码差异可以完全绕开：读写文件与检索请遵循「执行规约 → 工具优先」，用 `read` / `grep` / `glob` / `ls` 工具而非命令行文本处理。\n");
        } else if (shellMode == ShellMode.CMD) {
            sb.append("- **CMD 方言（必须遵守）**: 当前 shell 是 cmd.exe，以下 Unix 写法在此**不存在**，用后必报错，须按右侧改写：\n");
            sb.append("  - 丢弃错误输出：`2>/dev/null` → `2>nul`。\n");
            sb.append("  - 查看文件：`cat` → `type`；文本检索：`grep` → `findstr`；列目录：`ls` → `dir`。\n");
            sb.append("  - 系统信息：`uname -a`、`cat /etc/os-release` → `ver`、`systeminfo`。\n");
            sb.append("  - 其它：`which x` → `where x`；`export A=B` → `set A=B`；读环境变量用 `%NAME%`（不是 `$NAME`）。没有 `head` / `tail` 等价命令（取前 N 行可用 `more +N`）。\n");
            // 跨盘符切目录是 CMD 特有的陷：`cd D:\x` 不报错、也不切，后续命令全在错的目录里执行
            sb.append("  - 跨盘符切目录必须写 `cd /d D:\\proj`（或 `pushd D:\\proj`）：`cd D:\\proj` 既不报错也不会切过去，后续命令会静默地在原目录执行。\n");
            sb.append("  - 多步骤串联：`&&`（失败即停）可用；用 `&` 串联则前一步失败不会中断，且返回的 `[exit_code]` 只反映最后一步。\n");
            appendMergedStderrRule(sb);
            sb.append("  - 不存在任何 Unix 路径：`/dev/null`、`/etc`、`/usr`、`/tmp` 均无效；路径分隔符为 `\\`，临时目录用 `%TEMP%`。\n");
            // 多行命令会以批处理脚本形式执行，% 语义与单行命令不同，需向模型说明
            sb.append("  - `%` 的语义差异：单行命令按命令行语义（`for %i in (...) do ...`）；多行命令按批处理脚本语义执行，循环变量需写成 `%%i`，字面百分号需写成 `%%`。不确定时优先拆成单行命令执行。\n");
            sb.append("  - **读文件会乱码**：`type` / `findstr` 按当前代码页（非 UTF-8）解析文件，读含中文/日韩文/emoji 的 UTF-8 文件必乱码，**且无法在命令里可靠地修正**。读文件一律改用 `read` 工具，检索一律改用 `grep` 工具。\n");
            sb.append("  - 上述方言差异可以完全绕开：读写文件与检索请遵循「执行规约 → 工具优先」，用 `read` / `grep` / `glob` / `ls` 工具而非命令行文本处理。\n");
        }
    }


    /**
     * PowerShell 下的「Unix 别名陷阱」规约。
     *
     * <p>这一类错误比「命令不存在」更难自教：{@code ls}、{@code rm}、{@code cp}、{@code ps}
     * 在 PowerShell 里确实存在（都是 cmdlet 别名），模型看到命令能识别会认为自己写对了，
     * 但它们一律不接受 Unix 风格的短参数：{@code ls -la} / {@code rm -rf x} 报的是参数绑定错误，
     * 而不是「未知命令」。不明说就会反复重试同一写法。</p>
     *
     * <p>{@code curl} / {@code wget} 需分版本：Windows PowerShell 5.1 把两者别名到
     * {@code Invoke-WebRequest}（所以 {@code curl -s <url>} 必报错）；PowerShell 7+ 已移除这两个别名，
     * {@code curl} 直接指向系统自带的 {@code curl.exe}。</p>
     */
    private void appendPowerShellAliasTrapRule(StringBuilder sb, boolean powerShellCore) {
        sb.append("  - **Unix 别名是陷阱（命令存在但参数不兼容）**：`ls`、`rm`、`cp`、`mv`、`ps`、`cat` 在此都是 cmdlet 别名，")
                .append("不接受 Unix 短参数：`ls -la`、`rm -rf dir`、`cp -r a b`、`ps aux` 全部报参数绑定错误（不是命令不存在，重试同写法也不会好）。")
                .append("等价写法：`Get-ChildItem -Force`、`Remove-Item -Recurse -Force dir`、`Copy-Item -Recurse a b`、`Get-Process`。\n");
        if (powerShellCore) {
            sb.append("    - `curl` / `wget` 在 PowerShell 7+ 已不再是别名，指向真实的 `curl.exe`，Unix 参数可用。\n");
        } else {
            sb.append("    - `curl` / `wget` 在 Windows PowerShell 5.1 是 `Invoke-WebRequest` 的别名，`curl -s <url>` 必报错；要用真 curl 请写 `curl.exe -s <url>`。\n");
        }
    }

    /**
     * 「stderr 已合流」规约（仅 Windows 系 shell 输出）。
     *
     * <p>本工具在 Java 层已经 {@code redirectErrorStream(true)}，命令里再写 {@code 2>&1} 完全多余；
     * 而在 PowerShell 下它还有实实在在的危害：一旦把原生程序的 stderr 重定向进 PowerShell 的流，
     * 引擎会把每一行 stderr 包成 {@code NativeCommandError} 错误记录（额外输出「所在位置 行:1 字符:1」
     * 等四五行噪声），并且使整个 PowerShell 进程的退出码变成 1。实测：`mvn test` 明明 BUILD SUCCESS，
     * 只因为命令里写了 {@code 2>&1}，结果就带上了 {@code [exit_code=1]}——模型会据此误判为失败并无意义地重试。</p>
     */
    private void appendMergedStderrRule(StringBuilder sb) {
        sb.append("  - **不要在命令里写 `2>&1`**：stderr 已由系统自动合并到输出里，无需任何重定向。");
        if (shellMode == ShellMode.POWERSHELL) {
            sb.append("在 PowerShell 下额外写 `2>&1` 还会把原生程序的 stderr 转成错误记录，凭空多出「所在位置 行:1 字符:1 / NativeCommandError」几行噪声，")
                    .append("并让整条命令的退出码变成 1（成功也会被报成 `[exit_code=1]`）。只想丢弃 stderr 时用 `2>$null`。\n");
        } else {
            sb.append("只想丢弃 stderr 时用 `2>nul`。\n");
        }
    }

    /**
     * 追加编码规约（仅 PowerShell）。
     *
     * <p>必须告知模型「已经做了什么」，而不是只说「注意编码」：启动时已注入 UTF-8 前置
     * （参见 {@code ShellCommandFactory.POWERSHELL_PREAMBLE}），若模型不知道，就会自己叠一层
     * {@code chcp 65001} 或 {@code -Encoding UTF8}，反而引入新问题；更重要的是得告知它唯一的
     * 例外（真正的 GBK 文件需 {@code -Encoding Default}），否则遇到 GBK 文件时它无从下手。</p>
     */
    private void appendEncodingRules(StringBuilder sb, boolean powerShellCore) {
        sb.append("  - **编码（系统已前置处理，按此书写）**：本次会话的输入/输出/文件读写默认编码已统一为 UTF-8（包括 `Get-Content`、`Select-String`、`Import-Csv` 的读，以及 `Set-Content`、`Add-Content`、`Out-File` 和 `>` / `>>` 的写）。无需自行 `chcp`、设置 `$OutputEncoding` 或反复加 `-Encoding UTF8`。\n");
        sb.append("    - 例外：若已确认某文件是 GBK/ANSI 编码，必须显式写 `-Encoding Default` 才能读对。\n");
        if (powerShellCore == false) {
            sb.append("    - Windows PowerShell 5.1 的 UTF-8 写入带 BOM，本工具的 `read` / `edit` / `grep` 会自动忽略它，无需特殊处理。\n");
        }
        sb.append("    - 若输出仍有乱码，几乎一定是被调用的第三方程序自己按 ANSI 输出的，不要在命令里反复调编码；读文件内容请直接用 `read` / `grep` 工具。\n");
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
    // 描述里点明「多行脚本可直接传入、落盘与清理由系统负责」是刻意的能力声明：
    // 模型若不知道本工具自带脚本承载能力，就会退化成先用 write 在工作区生成临时脚本再执行——
    // 那些文件落在工作区（沙盒下 write 到不了系统临时目录），会污染 git 状态与后续 glob/grep，
    // 且没有任何清理保证。此处只讲能力、不设禁令，让模型基于「更省事」自行选择正确路径。
    @ToolMapping(
            name = "bash",
            description = "在当前终端执行非交互式命令。工具名叫 bash 仅为历史兼容，**实际 shell 未必是 bash**：请以系统提示中「Terminal 环境状态 → 终端类型」为准（可为 PowerShell / CMD / Unix shell），并按对应方言书写命令。支持逻辑路径（如 `cd @pool1/bin/tool/`）。多步逻辑可把多行脚本整段作为 command 传入，系统会按当前 shell 选择可靠的执行方案（必要时自动写入系统临时目录并在结束后删除）。含空格或特殊字符的参数请用引号包裹；输出编码由系统自动识别。"
    )
    public String bash(@Param(value = "command", description = "要执行的指令。") String command,
                       @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒") Integer timeout,
                       @Param(name = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符输出，超出保留首尾片段。读取大文件请改用 read 工具。") Integer maxOutputChars,
                       String __cwd) {

        // 统一安全校验（替代原来的内联检查）
        String violation = support.validateCommandNoKill(command);
        if (violation != null) return violation;

        Path workPath = getWorkPath(__cwd);
        Map<String, String> envs = new HashMap<>();

        String pyCmd = pythonCmd();
        String ndCmd = nodeCmd();
        if(Assert.isNotEmpty(pyCmd)) {
            envs.put("PYTHON", pyCmd);
        }

        if(Assert.isNotEmpty(ndCmd)) {
            envs.put("NODE", ndCmd);
        }

        String finalCommand;
        try {
            finalCommand = support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);
        } catch (SecurityException ex) {
            return "错误：" + ex.getMessage();
        }

        // OS 级沙盒包装（内核级强制隔离：Seatbelt / bwrap）
        // 仅当 sandboxSystemRestrict=true 时启用，将安全隔离的重活交给 OS 内核
        // 关闭后仅保留 Java 层最小自保护（kill PID / exit / rm -rf /），减少误伤
        ensureSandboxInitialized();
        if (sandboxEnabled && sandboxSystemRestrict && SandboxManager.isSandboxingEnabled()) {
            try {
                finalCommand = SandboxManager.wrapWithSandbox(
                        finalCommand, null, buildDynamicCustomConfig());
            } catch (Exception e) {
                SandboxLog.debug("Sandbox wrap failed, running without OS sandbox: " + e.getMessage());
            }
        }

        // 与 bash_start 共用 ShellCommandFactory：Unix 直接 shell -lc 执行；Windows 改走 prepare
        // （PowerShell 用 -EncodedCommand 避开命令文本代码页转换；CMD 默认 /d /c 直连，仅多行/非 ANSI/超长命令才落 .bat）
        if (shellCommandFactory.isWindowsShell()) {
            ShellCommandFactory.PreparedCommand prepared;
            try {
                prepared = shellCommandFactory.prepare(finalCommand);
            } catch (IOException ex) {
                return "错误：无法准备 Windows 执行方案: " + ex.getMessage();
            }
            if (prepared != null) {
                try {
                    return executor.executeCmd(workPath, prepared.argv(), envs, timeout, maxOutputChars, null);
                } finally {
                    prepared.cleanup();
                }
            }
        }
        return executor.executeCmd(workPath, shellCommandFactory.build(finalCommand), envs, timeout, maxOutputChars, null);
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
        SandboxRuntimeConfig dynamicCfg = buildDynamicCustomConfig();
        Path targetWorkPath = support.resolveCommandWorkPath(workPath, workdir, sandboxEnabled, sandboxAllowUserHome);
        Map<String, String> envs = new HashMap<>();

        String pyCmd = pythonCmd();
        String ndCmd = nodeCmd();
        if(Assert.isNotEmpty(pyCmd)) {
            envs.put("PYTHON", pyCmd);
        }
        if(Assert.isNotEmpty(ndCmd)) {
            envs.put("NODE", ndCmd);
        }

        String finalCommand;
        try {
            finalCommand = support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);
        } catch (SecurityException ex) {
            return "错误：" + ex.getMessage();
        }

        // OS 级沙盒包装（内核级强制隔离：Seatbelt / bwrap）
        // 仅当 sandboxSystemRestrict=true 时启用，将安全隔离的重活交给 OS 内核
        // 关闭后仅保留 Java 层最小自保护（kill PID / exit / rm -rf /），减少误伤
        ensureSandboxInitialized();
        if (sandboxEnabled && sandboxSystemRestrict && SandboxManager.isSandboxingEnabled()) {
            try {
                finalCommand = SandboxManager.wrapWithSandbox(
                        finalCommand, null, buildDynamicCustomConfig());
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
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, fs());

        if (!Files.exists(target)) {
            return "错误：路径不存在";
        }

        if (Boolean.TRUE.equals(recursive)) {
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");
            support.generateTreeInternal(support.getSandboxPolicyRoot(workPath, path), target, 0, 3, "", sb, Boolean.TRUE.equals(showHidden), sandboxEnabled, fs());
            return sb.toString();
        } else {
            return support.flatListLogic(workPath, support.getSandboxPolicyRoot(workPath, path), target, path, Boolean.TRUE.equals(showHidden), sandboxEnabled, fs());
        }
    }

    // --- 3. 读取内容 ---
    @ToolMapping(name = "read", description = "读取文件内容。修改文件前先通过此工具确认最新的文本内容、缩进和换行符。支持大文件分页。支持逻辑路径（如 @pool）。优先尝试不限制读取（即尝试完整读取）")
    public String read(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "offset", required = false, defaultValue = "1", description = "开始读取的行号（默认从1开始索引）") Integer offset,
                       @Param(value = "limit", required = false, description = "需要读取的最大行数（默认不限制）。注意：单次读取受最大物理长度保护，如果触发截断，请根据输出提示调整 offset 分页读取。") Integer limit,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, filePath, false, sandboxEnabled, sandboxAllowUserHome, fs());
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
                if (hasData == false && startLine0 == 0) {
                    // 首行可能带 UTF-8 BOM（PowerShell 5.1 写出的文件必带）：剥离后再交给模型，
                    // 否则模型拿到的首行多一个不可见字符，回头用 edit 按该行匹配会无声失败
                    line = TerminalSupport.stripUtf8Bom(line);
                }
                hasData = true;

                // 使用 long 类型的 count 防止溢出，格式化为行号
                String lineOutput = String.format("%6d | %s\n", startLine0 + count + 1, line);

                // 实时检测物理长度限制 (Char Size)
                if (contentBuilder.length() + lineOutput.length() > support.maxCharacterLimit) {
                    isByteTruncated = true;
                    // 边界：单行本身超过物理上限（如 minified JS/CSS、单行大 JSON）时 contentBuilder 仍为空，
                    // 直接 break 会导致无内容输出且 actualEndLine 不前进，分页提示 offset 与本次相同 → AI 重试死循环。
                    // 故输出该行的安全截断片段并让行号前进一行，保证分页可推进。
                    if (contentBuilder.length() == 0) {
                        String prefix = String.format("%6d | ", startLine0 + count + 1);
                        int slice = Math.max(0, Math.min(line.length(), support.maxCharacterLimit - prefix.length() - 16));
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
        sb.append(String.format("--- File: %s (Lines: %d - %d, Size: %.2f KB) ---\n",
                filePath, startLine0 + 1, actualEndLine, fileSize / 1024.0));
        sb.append("--------------------------------------------------\n");
        sb.append(contentBuilder);

        // 4. 动态提示与分页引导
        if (isByteTruncated || (limit != null && hasMore)) {
            sb.append("\n\n--- [内容未完] ---");
            if (isByteTruncated) {
                sb.append("\n警告：因单次读取物理长度限制（" + (support.maxCharacterLimit / 1024) + "KB），内容已被截断。");
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
    // 「不用于临时脚本」写在此处而非 bash 描述里：误用的决策点发生在调用 write 的那一刻。
    // 沙盒下本工具只能写工作区，产出的脚本会进 git 状态且无人清理；多行脚本交给 bash 更省事。
    @ToolMapping(name = TOOL_WRITE, description = "创建新文件或覆盖现有文件。仅用于项目产物；要执行的多行脚本请直接交给 `bash`，不必先落成临时脚本文件。")
    public String write(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                        @Param(value = PARAM_CONTENT, description = "完整文本内容。") String content,
                        String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, filePath, true, sandboxEnabled, sandboxAllowUserHome, fs());

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return "文件成功写入: " + filePath;
    }


    @ToolMapping(
            name = TOOL_EDIT,
            description = "对文件进行文本替换，自动忽略行首行尾空白差异。支持单次调用执行一处或多处编辑。具有原子性：所有编辑成功才会写入，否则全部回滚。"
    )
    public String edit(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = PARAM_EDITS, description = "编辑操作列表") List<EditOp> edits,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, filePath, true, sandboxEnabled, sandboxAllowUserHome, fs());

        if (!Files.exists(target)) {
            return "错误：文件不存在，无法进行编辑。";
        }

        String rawContent = new String(Files.readAllBytes(target), fileCharset);
        // BOM 与编辑逻辑解耦：匹配、行号、缩进推断全部在无 BOM 的正文上进行（否则首行匹配必失败），
        // 写回时再按原样补回，避免 edit 顺手改变文件的字节形态
        boolean hadBom = TerminalSupport.hasUtf8Bom(rawContent);
        String originalContent = TerminalSupport.stripUtf8Bom(rawContent);

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

            String finalOld = support.normalizeNewlines(originalContent, edit.oldStr);

            if (Boolean.TRUE.equals(edit.replaceAll)) {
                if (!originalContent.contains(finalOld)) {
                    return String.format("预检查失败（操作 #%d）: 全文替换匹配失败。请确认 old_str 的内容在文件中存在（注意缩进和空格差异会被忽略）。", i + 1);
                }
                continue;
            }

            TerminalSupport.MatchResult match = support.findAtStartLine(originalContent, finalOld, edit.oldStrStartLine);
            if (match != null) {
                continue;
            }

            int firstIndex = originalContent.indexOf(finalOld);
            if (firstIndex == -1) {
                // 给出精准的诊断信息
                String diag = support.findLooseMatchDiagnostics(originalContent, finalOld, edit.oldStrStartLine);
                if (diag != null) {
                    return String.format("预检查失败（操作 #%d）: 内容匹配失败 — %s。请检查 old_str 的内容是否与文件对应。",
                            i + 1, diag);
                }
                // diag 返回 null 表示宽松匹配成功（边缘情况），允许通过预检查
                continue;
            }

            if (originalContent.lastIndexOf(finalOld) != firstIndex) {
                return String.format("预检查失败（操作 #%d）: 文本块在指定 old_StrStartLine 处未精确匹配，且在文件中不唯一。请提供正确的 old_StrStartLine 或增加上下文行。", i + 1);
            }
        }

        String workingContent = originalContent;
        List<Integer> executionOrder = buildEditExecutionOrder(edits);
        // 顺序应用所有编辑；当所有操作都是带 old_StrStartLine 的单点替换时，按行号倒序执行，避免前面的修改影响后面的行号。
        for (Integer editIndex : executionOrder) {
            EditOp edit = edits.get(editIndex);
            try {
                workingContent = support.applyEditLogic(workingContent, edit.oldStr, edit.newStr, Boolean.TRUE.equals(edit.replaceAll), edit.oldStrStartLine);
            } catch (IllegalArgumentException e) {
                return String.format("执行失败（操作 #%d）: %s。可能是由于前面的修改破坏了此处的匹配上下文，请尝试分多次调用 edit。", editIndex + 1, e.getMessage());
            }
        }

        // 原子性保存（原文件带 BOM 则保持带 BOM）
        Files.write(target, (hadBom ? TerminalSupport.UTF8_BOM + workingContent : workingContent).getBytes(fileCharset));

        return String.format("文件 %s 成功完成 %d 处修改。", filePath, edits.size());
    }

    // --- 5. 搜索工具 ---
    @ToolMapping(name = "grep", description = "递归搜索内容。返回 '路径:行号:内容'。在不确定文件位置时先执行搜索。支持逻辑路径（如 @pool）。pattern 支持正则表达式匹配。")
    public String grep(@Param(value = "pattern", description = "搜索内容，支持正则表达式匹配") String pattern,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       @Param(value = "include", required = false, description = "要包含的文件模式（如 \"*.js\"、\"*.{ts,tsx}\"）") String include,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, fs());

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
        final FilesystemConfig fsConfig = fs();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, dir) || support.isIgnored(target, dir) || support.isReadDenied(policyRoot, dir, sandboxEnabled, fsConfig)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, file) || support.isIgnored(target, file) || support.isReadDenied(policyRoot, file, sandboxEnabled, fsConfig)) {
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
                        if (lineNum == 1) {
                            // 首行可能带 UTF-8 BOM：不剥除则 `^xxx` 这类锚定到行首的正则在首行永不命中
                            line = TerminalSupport.stripUtf8Bom(line);
                        }
                        if (finalPattern != null ? finalPattern.matcher(line).find() : line.contains(pattern)) {
                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 1000) {
                                trimmedLine = trimmedLine.substring(0, 1000) + "...(line truncated)";
                            }

                            String displayPath = support.formatDisplayPath(workPath, path, target, file, sandboxEnabled);
                            sb.append(displayPath).append(":").append(lineNum).append(": ").append(trimmedLine).append("\n");

                            // 发现匹配后立即检查长度，防止 StringBuilder 过载
                            if (sb.length() > support.maxCharacterLimit) {
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

        if (sb.length() >= support.maxCharacterLimit) {
            sb.append("\n\n--- [内容未完] ---");
            sb.append("\n警告：搜索结果过多，已达到 " + (support.maxCharacterLimit / 1024) + "KB 限制并截断。请缩小搜索路径或关键词。");
        }

        return sb.length() == 0 ? "未找到结果。" : sb.toString();
    }

    @ToolMapping(name = "glob", description = "按通配符模式（如 **/*.java）搜索文件。确定文件范围的最高效工具。支持逻辑路径（如 @pool）。")
    public String glob(@Param(value = "pattern", description = "glob 模式。") String pattern,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, fs());

        String fixedPattern = pattern.replace("\\", "/");
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fixedPattern);

        List<String> results = new ArrayList<>();
        Path policyRoot = support.getSandboxPolicyRoot(workPath, path);
        final FilesystemConfig fsConfig = fs();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, dir) || support.isIgnored(target, dir) || support.isReadDenied(policyRoot, dir, sandboxEnabled, fsConfig)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, file) || support.isIgnored(target, file) || support.isReadDenied(policyRoot, file, sandboxEnabled, fsConfig)) {
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
    private List<Integer> buildEditExecutionOrder(List<EditOp> edits) {
        boolean allLineScopedSingleReplace = true;
        for (EditOp edit : edits) {
            if (Boolean.TRUE.equals(edit.replaceAll) || edit.oldStrStartLine == null || edit.oldStrStartLine <= 0) {
                allLineScopedSingleReplace = false;
                break;
            }
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            order.add(i);
        }

        if (allLineScopedSingleReplace) {
            order.sort((a, b) -> Integer.compare(edits.get(b).oldStrStartLine, edits.get(a).oldStrStartLine));
        }

        return order;
    }

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
        // sandboxAllowUserHome=false 时，阻止 ~ 路径。
        // 复用生产路径同款判定（containsUserHomePath），避免测试/生产逻辑分裂。
        if (sandboxEnabled && !sandboxAllowUserHome && support.containsUserHomePath(command)) {
            return "错误：sandboxAllowUserHome 已禁用，不允许使用 ~ 路径。";
        }
        return null;
    }

    String translateCommandToEnv(String command, java.util.Map<String, String> envs) {
        return support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);
    }

    public static class EditOp {
        @Param(value = "old_str",
                description = "待替换的文本块。比较时会自动忽略每行的首尾空白差异（缩进、末尾空格等），并容忍末尾多余空行。结合 old_StrStartLine 定位替换起点。")
        public String oldStr;
        @Param(value = "old_StrStartLine",
                description = "old_str 在 read 输出中的起始行号，用于定位替换起点；系统会忽略缩进和末尾空白差异进行匹配。")
        public Integer oldStrStartLine;

        @Param(value = "new_str",
                description = "替换后的新内容")
        public String newStr;

        @Param(value = "replace_all", required = false, defaultValue = "false",
                description = "是否替换所有匹配项。为 true 时，会忽略 old_StrStartLine，全文替换所有匹配文本（同样忽略首尾空白差异）。")
        public Boolean replaceAll = false;
    }
}
