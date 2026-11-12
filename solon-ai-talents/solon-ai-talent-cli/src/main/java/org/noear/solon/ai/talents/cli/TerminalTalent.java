/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.sandbox.SandboxManager;
import org.noear.solon.ai.sandbox.SandboxViolationStore;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.mount.*;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.WriteOptions;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
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
    public static final String PARAM_EDITS = "edits";

    /** 本轮会话工作目录（由宿主经工具上下文注入，并同步到 prompt 属性） */
    private static final String ATTR_CWD = "__cwd";


    private final ShellCommandFactory shellCommandFactory;
    private final ShellMode shellMode;
    private final TerminalSupport support;

    //沙盒模式：只能访问相对路径或逻辑路径；（否则为）开放模式：可以访问绝对路径
    private boolean sandboxEnabled = true;
    //允许访问用户主目录（~ 路径）。仅在 sandboxEnabled=true 时有意义；默认 true 保持向后兼容
    private boolean sandboxAllowUserHome = false;
    //OS 内核级沙盒限制：是否启用 Seatbelt/bwrap 等系统级强制隔离。
    //关闭后仅依赖 Java 层自保护 + 系统提示词软约束，可减少误伤（如构建工具被拦截）。
    //仅在 sandboxEnabled=true 时有意义；默认 false（轻量模式）
    private boolean sandboxSystemRestrict = true;
    /** 是否由本实例自行初始化进程级 OS 沙盒（见 setSandboxSelfBootstrap） */
    private volatile boolean sandboxSelfBootstrap = true;
    /**
     * 单次读取/搜索的最大物理长度限制（字符数）。
     * 默认 128KB (128 * 1024)。
     */
    private int maxCharacterLimit = 128 * 1024;
    private final MountManager mountManager; // 引入挂载管理器
    private @Nullable SandboxRuntimeConfig sandboxConfig;
    private final SandboxViolationStore violationStore = new SandboxViolationStore(Collections.emptyMap());

    /** 运行时探测失败后的重试间隔：失败结果在窗口期内直接返回 null，避免每次对话都 fork 探测（最长等 2s 超时） */
    private static final long PROBE_RETRY_INTERVAL_MS = 60_000;
    /** 虚拟来源每个文件的实际读取上限（字节），与本地 grep 的 10MB 门槛一致。 */
    private static final int MAX_VIRTUAL_FILE_BYTES = 10 * 1024 * 1024;

    private volatile String pythonCmd;
    private volatile String nodeCmd;
    /** 上次 python 探测失败时间（0 表示无失败记录） */
    private volatile long pythonProbeFailedAt;
    /** 上次 node 探测失败时间（0 表示无失败记录） */
    private volatile long nodeProbeFailedAt;
    /**
     * 运行时探测锁。
     *
     * <p>专用锁而不是 {@code synchronized (this)}：临界区内要 fork 探测进程（最长等 2s），
     * 用实例监视器会让所有 {@code synchronized (this)} 的方法一起排队；在 JDK 21~23 上
     * 阻塞在 synchronized 内还会钉住载体线程。字段本身是 volatile，锁只保护「探测一次」。</p>
     */
    private final ReentrantLock pythonProbeLock = new ReentrantLock();
    private final ReentrantLock nodeProbeLock = new ReentrantLock();

    protected Charset fileCharset = StandardCharsets.UTF_8;
    protected final ProcessExecutor executor = new ProcessExecutor();
    protected final TerminalSessionManager bashSessionManager;
    //异步会话模式：启用后提供 bash_start/wait/stop 工具
    private boolean bashAsyncEnabled = false;

    /**
     * 写入后的诊断钩子：入参为文件绝对路径，返回待追加到工具输出的诊断片段（无则 null）。
     *
     * <p>诊断不能指望模型主动去查：只有把它贴在 write/edit 的返回值上，模型才会在下一轮看到。
     * 用钩子而非直接依赖是为了不让 talent-cli 反向依赖 talent-lsp（由 harness 层接线）。
     */
    private Function<Path, String> fileDiagnosticsHook;

    /**
     * 读取后的预热钩子：入参为文件绝对路径。实现方须自行异步化，不得阻塞读取返回。
     */
    private Consumer<Path> fileWarmupHook;

    /**
     * 虚拟来源的物化执行缓存；默认位于用户级 ~/&lt;harnessHome&gt;cache/mount，
     * 测试或特殊部署可通过包内构造器注入其它缓存根。
     */
    private final MountMaterializer materializer;

    /**
     * 宿主配置目录（规范化后以 / 结尾，如 {@code .solon/}）。受保护的 commands / agents 子目录、
     * 物化缓存目录与搜索忽略目录都由它推导；经 harness 引擎构建时等于引擎的 harnessHome。
     */
    private final String harnessHome;

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

    /**
     * 宿主配置目录（规范化后以 / 结尾）
     */
    public String getHarnessHome() {
        return harnessHome;
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
     * 设置写入后的诊断钩子（见 {@link #fileDiagnosticsHook}）
     */
    public void setFileDiagnosticsHook(Function<Path, String> fileDiagnosticsHook) {
        this.fileDiagnosticsHook = fileDiagnosticsHook;
    }

    /**
     * 设置读取后的预热钩子（见 {@link #fileWarmupHook}）
     */
    public void setFileWarmupHook(Consumer<Path> fileWarmupHook) {
        this.fileWarmupHook = fileWarmupHook;
    }

    /**
     * 把诊断片段追加到工具输出末尾。
     *
     * <p>钩子里的任何异常都必须吞掉：语言服务器不可用是常态（未安装/不支持该文件类型），
     * 绝不能让它把一次已经成功的写入变成失败。
     */
    private String withDiagnostics(String result, Path target) {
        Function<Path, String> hook = this.fileDiagnosticsHook;
        if (hook == null) {
            return result;
        }

        try {
            String diagnostics = hook.apply(target);
            if (diagnostics == null || diagnostics.isEmpty()) {
                return result;
            }
            return result + "\n\n" + diagnostics;
        } catch (Throwable e) {
            return result;
        }
    }

    private void warmupFile(Path target) {
        Consumer<Path> hook = this.fileWarmupHook;
        if (hook == null) {
            return;
        }

        try {
            hook.accept(target);
        } catch (Throwable e) {
            //预热失败只影响后续诊断/导航的响应速度，不影响读取结果
        }
    }


    /**
     * 设置沙盒配置（支持读写分离、网络过滤、违规监控等高级功能）
     */
    public void setSandboxConfig(SandboxRuntimeConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig;
    }

    /**
     * 是否由本实例在首次执行命令时初始化进程级 OS 沙盒（缺省 true，适合 CLI/桌面单引擎）。
     *
     * <p>{@code SandboxManager} 是 JVM 单例：网络代理与域名白名单按「第一次初始化」的配置全局生效，
     * 初始化失败的 reset 也影响全进程。一个进程里有多个引擎（SaaS 引擎池）时应设为 false，
     * 由宿主在启动时调用一次 {@link SandboxBootstrap#initOnce}；未初始化就执行命令会被拒绝（不会裸跑）。
     * 文件系统边界不受影响：每次调用仍按本引擎的挂载重新生成。</p>
     */
    public void setSandboxSelfBootstrap(boolean sandboxSelfBootstrap) {
        this.sandboxSelfBootstrap = sandboxSelfBootstrap;
    }

    public boolean isSandboxSelfBootstrap() {
        return sandboxSelfBootstrap;
    }

    /**
     * 设置受保护的宿主状态子路径（相对 harnessHome，如 {@code sessions}、{@code memory}）。
     *
     * <p>这些目录里是其它会话/用户的数据：文件工具读写都拒绝，启用 OS 沙盒时 bash 也读写不到。</p>
     */
    public void setProtectedStatePaths(Collection<String> subPaths) {
        support.setProtectedStatePaths(subPaths);
    }

    public List<String> getProtectedStatePaths() {
        return support.getProtectedStatePaths();
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
            if (!sandboxSelfBootstrap) {
                // 进程级沙盒由宿主统一引导：这里既不自建也不 reset（避免一个引擎影响整个进程）
                throw new IllegalStateException("OS sandbox is not bootstrapped: "
                        + "call SandboxBootstrap.initOnce(...) at process start");
            }
            // 进程内只有一个初始化入口（与宿主引导共用同一把锁）；失败已复位并抛出，
            // sandboxSystemRestrict 是显式的强制安全选项，初始化失败不能降级成裸执行。
            SandboxBootstrap.initOnce(sandboxConfig != null ? sandboxConfig : buildDefaultSandboxConfig());
        }
    }

    /**
     * 构建动态沙箱配置：合并用户配置的 sandboxConfig（网络、seccomp 等静态部分）
     * 与当前挂载点的文件系统路径白名单（动态部分）。
     *
     * <p>文件系统路径每次从 mountManager 实时获取，确保挂载点增删变化后沙箱边界实时生效。
     * 返回值可作为 SandboxManager.wrapWithSandbox() 的 customConfig 参数传入。
     *
     * @param workPath 本轮命令的工作根（由 __cwd 解析）；可为 null
     */
    private SandboxRuntimeConfig buildDynamicCustomConfig(@Nullable Path workPath) {
        // 1) 从 mountManager 构建当前最新的文件系统白名单
        FilesystemConfig dynamicFs = buildDynamicFilesystemConfig(workPath);

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
     *
     * @param workPath 本轮命令的工作根（由 __cwd 解析）；可为 null
     */
    private FilesystemConfig buildDynamicFilesystemConfig(@Nullable Path workPath) {
        List<String> allowWrite = new ArrayList<>();
        List<String> allowRead = new ArrayList<>();

        // 1) 工作区目录：允许读写（startsWith 匹配覆盖所有子路径）
        String workDir = mountManager.getWorkDir();
        if (workDir != null) {
            allowWrite.add(workDir);
            allowRead.add(workDir);
        }

        // 1.1) 本轮工作根（__cwd）：会话工作目录可以不在工作区下（如桌面端 goal 把会话目录设为目标项目）。
        //      文件工具与命令工作目录都以它为根放行，OS 白名单必须同口径，否则同一路径文件工具能写、bash 被内核拒。
        if (workPath != null && (workDir == null
                || !workPath.startsWith(Paths.get(workDir).toAbsolutePath().normalize()))) {
            String cwdStr = workPath.toString();
            allowWrite.add(cwdStr);
            allowRead.add(cwdStr);
        }

        // 2) 用户级物化缓存：bash 需要读取/执行其中的脚本；启用 OS 级沙盒时禁止命令写入。
        // 即使 user.home 整体被 allowWrite 放行，也通过 denyWrite 保持缓存只读。未启用 OS 沙盒时，
        // MountMaterializer 的 manifest 会在下一次缓存命中时检测并修复普通文件改动。
        List<String> denyWrite = new ArrayList<>();
        if (materializer != null) {
            String cacheRoot = materializer.getCacheRoot().toString();
            allowRead.add(cacheRoot);
            denyWrite.add(cacheRoot);
        }

        // 2.1) 宿主配置目录下的命令与子代理定义：与文件工具层 mandatory deny 同口径，禁止 bash 改写
        //      （否则文件工具拦住了，模型仍可用 echo > 绕过）。工作区根与本轮工作根各登记一份。
        // 2.2) 宿主状态目录（sessions / memory 等，见 setProtectedStatePaths）：读写都禁止，
        //      与文件工具层同口径（否则模型可以用 cat 读到其它会话的快照）。
        List<String> denyRead = new ArrayList<>();
        List<String> statePaths = support.getProtectedStatePaths();
        for (String root : new String[]{workDir, workPath == null ? null : workPath.toString()}) {
            if (root != null) {
                Path base = Paths.get(root).toAbsolutePath().normalize();
                for (String sub : new String[]{"commands", "agents"}) {
                    String p = base.resolve(harnessHome + sub).normalize().toString();
                    if (!denyWrite.contains(p)) {
                        denyWrite.add(p);
                    }
                }
                for (String rel : statePaths) {
                    String p = base.resolve(rel).normalize().toString();
                    if (!denyWrite.contains(p)) {
                        denyWrite.add(p);
                    }
                    if (!denyRead.contains(p)) {
                        denyRead.add(p);
                    }
                }
            }
        }

        // 3) 所有挂载点：凡是 shell 可达的来源（声明了 shellAccessible 且有本地根路径，不限于 FileMountSource），
        //    按可写性加入对应列表（无需 /** 后缀，startsWith 匹配覆盖子路径）。纯虚拟来源（Classpath / JDBC 等）
        //    或未开放 shell 的来源不参与 OS 沙盒白名单——文件工具走 MountSource.openRead，本就不依赖 OS 白名单。
        //    注意：这里按 enabled 判定而非 visible——isVisible 只是展示元数据，隐藏挂载（如内置 @harness）
        //    的本地根目录仍必须纳入 OS 沙盒白名单，否则隐藏挂载上的 bash 访问会被 OS 层拒绝，
        //    与 translateCommandToEnv 的翻译范围（同样只按 enabled）出现不一致。
        for (Mount mount : mountManager.getMounts()) {
            if (mount.isEnabled() && mount.getType() != MountType.AGENTS) {
                Path localRoot = TerminalSupport.shellLocalRoot(mount.getSource()).orElse(null);
                if (localRoot != null) {
                    String pathStr = localRoot.toString();
                    allowRead.add(pathStr);
                    if (mount.isWritable()) {
                        allowWrite.add(pathStr);
                    }
                }
            }
        }

        // 4) 用户主目录：当启用 sandboxAllowUserHome 时加入读写白名单
        //    解决 agent-browser（npx ~/.npm/_npx）、npm 缓存（~/.npm/_cacache）
        //    等工具需要在用户主目录下读写的问题；物化缓存仍受 denyWrite 保护。
        if (sandboxAllowUserHome) {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                allowRead.add(userHome);
                allowWrite.add(userHome);
            }
        }

        return new FilesystemConfig(
                denyRead.isEmpty() ? null : denyRead,
                allowRead.isEmpty() ? null : allowRead,
                allowWrite.isEmpty() ? null : allowWrite,
                denyWrite.isEmpty() ? null : denyWrite,
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
        FilesystemConfig fsConfig = buildDynamicFilesystemConfig(null);

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

    /**
     * 按缺省宿主配置目录（{@code .soloncode/}）构建；经 harness 引擎使用时由引擎传入实际的 harnessHome。
     */
    public TerminalTalent(MountManager mountManager) {
        this(mountManager, (String) null);
    }

    /**
     * @param harnessHome 宿主配置目录（相对工作区，如 {@code .solon/}）；空白时按 {@code .soloncode/}。
     *                    {@code <harnessHome>commands}、{@code <harnessHome>agents} 禁止模型改写，
     *                    物化缓存位于 {@code ~/<harnessHome>cache/mount}
     */
    public TerminalTalent(MountManager mountManager, String harnessHome) {
        this(mountManager, ShellCommandFactory.detect(),
                TerminalSupport.defaultMaterializeCacheRoot(mountManager.getWorkDir(), harnessHome),
                harnessHome);
    }

    /**
     * 显式指定 shell 方案的构造器（主要给测试用：引导词需要按 CMD / POWERSHELL / UNIX_SHELL
     * 三种方言分别验证，不能只依赖当前宿主机的探测结果）。
     */
    TerminalTalent(MountManager mountManager, ShellCommandFactory shellCommandFactory) {
        this(mountManager, shellCommandFactory,
                TerminalSupport.defaultMaterializeCacheRoot(mountManager.getWorkDir(), null));
    }

    /** 测试/特殊部署用：显式指定用户级物化缓存根，避免测试污染真实用户目录。 */
    TerminalTalent(MountManager mountManager, ShellCommandFactory shellCommandFactory, Path cacheRoot) {
        this(mountManager, shellCommandFactory, cacheRoot, null);
    }

    TerminalTalent(MountManager mountManager, ShellCommandFactory shellCommandFactory, Path cacheRoot, String harnessHome) {
        if (shellCommandFactory == null) {
            throw new IllegalArgumentException("shellCommandFactory is required");
        }
        this.mountManager = mountManager;
        this.harnessHome = TerminalSupport.normalizeHarnessHome(harnessHome);
        String hhTop = TerminalSupport.harnessHomeTopDir(this.harnessHome);
        if (hhTop != null) {
            ignoreDirs.add(hhTop);
        }

        this.shellCommandFactory = shellCommandFactory;
        this.shellMode = shellCommandFactory.getShellMode();
        this.bashSessionManager = new TerminalSessionManager(shellCommandFactory);

        this.support = new TerminalSupport(mountManager, ignoreDirs, shellMode, this.harnessHome);
        this.support.setMaxCharacterLimit(this.maxCharacterLimit);

        // 虚拟来源（classpath 等）的物化执行缓存默认放在用户级 ~/<harnessHome>cache/mount，
        // 按 alias/fingerprint 复用，不随工作区和 Talent 生命周期删除。
        if (cacheRoot != null) {
            this.materializer = new MountMaterializer(cacheRoot);
            this.support.setMaterializer(this.materializer);
        } else {
            this.materializer = null;
        }
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
            pythonProbeLock.lock();
            try {
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
            } finally {
                pythonProbeLock.unlock();
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
            nodeProbeLock.lock();
            try {
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
            } finally {
                nodeProbeLock.unlock();
            }
        }
        return cmd;
    }

    @Override
    public String description(Prompt prompt) {
        return CliI18n.get(AiI18n.localeOf(prompt), CliI18nKeys.TERMINAL_DESCRIPTION);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        Locale locale = AiI18n.localeOf(prompt);
        StringBuilder sb = new StringBuilder();

        // 当前 shell 是否为 Windows 系（CMD / PowerShell）。引导词中所有涉及
        // 命令方言、进程管理、路径语义的表述都必须按此分支给出，
        // 否则会向模型泄漏错误的平台先验（如在 Windows 上建议 pkill / 2>/dev/null）。
        boolean windowsShell = shellCommandFactory.isWindowsShell();

        sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_TITLE)).append("\n");
        String sandboxMode = sandboxEnabled
                ? CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SANDBOX_ON)
                : CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SANDBOX_OFF);
        sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SANDBOX, "mode", sandboxMode)).append("\n");
        sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_OS,
                "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"))).append("\n");
        sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SHELL,
                "mode", shellMode, "summary", shellDialectSummary(locale))).append("\n");


        // 在 getInstruction 增加以下逻辑
        sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SELF_PROTECT, "pid", Utils.pid())).append("\n");
        if (windowsShell) {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_KILL_WINDOWS)).append("\n");
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_KILL_TIP_WINDOWS)).append("\n");
        } else {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_KILL_UNIX)).append("\n");
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_KILL_TIP_UNIX)).append("\n");
        }

        // 这里只列「会被 validateCommandNoKill 直接拦下」的两类硬禁令：exit 与根/系统目录删除。
        // 泛化条款（如「不要改变宿主系统状态」）没有可判定边界、也无代码兜底，写了只是占 token，
        // 真正的强制隔离交给 sandboxSystemRestrict + OS 沙盒。
        sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_FORBIDDEN)).append("\n");
        if (windowsShell) {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_FORBIDDEN_RM_WINDOWS)).append("\n");
        } else {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_FORBIDDEN_RM_UNIX)).append("\n");
        }

        sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_RUNTIME_TITLE)).append(" \n");
        String pyCmd = pythonCmd();
        String ndCmd = nodeCmd();
        if(Assert.isNotEmpty(pyCmd)) {
            // 占位符必须按 shell 方言给出：CMD 为 %PYTHON%、PowerShell 为 $env:PYTHON、Unix 为 $PYTHON
            sb.append("  ").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_RUNTIME_PYTHON,
                    "cmd", pyCmd, "env", support.getEnvPlaceholder("PYTHON"))).append("\n");
        }
        if(Assert.isNotEmpty(ndCmd)) {
            sb.append("  ").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_RUNTIME_NODE,
                    "cmd", ndCmd, "env", support.getEnvPlaceholder("NODE"))).append("\n");
        }

        // 动态判断是否有可写挂载点。以下三个 has* 均为展示层判定（决定引导词写法），
        // 必须与 mount_list 的过滤口径一致（enabled + visible），否则会出现“列表里没有挂载、
        // 引导词却按有挂载的形态描述”的矛盾（如内置 @harness 隐藏后仍触发挂载点说明段落）。
        boolean hasWriteableMount = mountManager.getMounts().stream()
                .anyMatch(m -> m.isEnabled() && m.isVisible() && m.getType() != MountType.AGENTS && m.isWritable());

        boolean hasMount = mountManager.getMounts().stream()
                .anyMatch(m -> m.isEnabled() && m.isVisible() && m.getType() != MountType.AGENTS);
        boolean hasLocalMount = mountManager.getMounts().stream()
                .anyMatch(m -> m.isEnabled() && m.isVisible() && m.getType() != MountType.AGENTS
                        && TerminalSupport.shellLocalRoot(m.getSource()).isPresent());
        boolean hasMaterializableMount = mountManager.getMounts().stream()
                .anyMatch(m -> m.isEnabled() && m.isVisible() && m.getType() != MountType.AGENTS
                        && !TerminalSupport.shellLocalRoot(m.getSource()).isPresent()
                        && m.getSource().capabilities().isMaterializable());

        sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PATH_TITLE)).append(" \n");
        sb.append("  ").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_WORKSPACE,
                "name", workspaceNameHint(prompt, locale))).append("\n");

        if (hasMount) {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_MOUNTS)).append("\n");
        }

        // 挂载点清单表格。shell 属性显式声明该挂载是否可在 bash 命令中使用逻辑路径：
        // 本地来源（shell=true）经 translateCommandToEnv 翻译为环境变量占位符后可 cd 可执行；
        // 虚拟来源（shell=materialized，如 classpath/jdbc）引用时按需物化到用户级持久缓存后执行，
        // 物化失败会直接返回错误，不会把原始 @alias 路径交给 shell。env/scheme 保持原有语义。
        if(hasMount) {
            sb.append("\n<mount_list>\n");
            for (Mount mount : mountManager.getMounts()) {
                if (mount.isEnabled() && mount.isVisible() && mount.getType() != MountType.AGENTS) {
                    sb.append("  <mount alias=\"").append(mount.getAlias()).append("\"");
                    if (Assert.isNotEmpty(mount.getDescription())) {
                        sb.append(" description=\"").append(mount.getDescription()).append("\"");
                    }
                    sb.append(" type=\"").append(mount.getType()).append("\"");
                    sb.append(" writeable=\"").append(mount.isWritable()).append("\"");
                    if (TerminalSupport.shellLocalRoot(mount.getSource()).isPresent()) {
                        sb.append(" shell=\"true\" env=\"").append(support.getEnvPlaceholder(support.toMountEnvKey(mount.getAlias()))).append("\"");
                    } else if (mount.getSource().capabilities().isMaterializable()) {
                        // 物化执行：虚拟来源（classpath 等受信来源）引用时会按需落地到用户级持久缓存再执行，
                        // 模型侧仍直接写 @alias 逻辑路径，与 shell=true 用法一致（可 cd、可作脚本路径）。
                        sb.append(" shell=\"materialized\" scheme=\"").append(mount.getSource().getScheme())
                          .append("\" env=\"").append(support.getEnvPlaceholder(support.toMountEnvKey(mount.getAlias()))).append("\"");
                    } else {
                        sb.append(" shell=\"false\" scheme=\"").append(mount.getSource().getScheme()).append("\"");
                    }
                    sb.append(" />\n");
                }
            }
            sb.append("</mount_list>\n");
        }


        if (sandboxEnabled) {
            if (hasMount) {
                sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SECURITY_SANDBOX_MOUNTS)).append("\n");
            } else {
                sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SECURITY_SANDBOX)).append("\n");
            }

            if (sandboxAllowUserHome) {
                sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_HOME_ON)).append("\n");
            } else {
                sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_HOME_OFF)).append("\n");
            }
        } else {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_SECURITY_OPEN)).append("\n");
        }

        sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_RULES_TITLE)).append("\n");

        if(hasMount) {
            if (hasWriteableMount) {
                sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_MOUNT_WRITABLE)).append("\n");
            } else {
                sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_MOUNT_READONLY)).append("\n");
            }
        }

        if (sandboxEnabled) {
            if (hasLocalMount || hasMaterializableMount) {
                sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_SANDBOX_MOUNTS)).append("\n");
            } else {
                sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_SANDBOX)).append("\n");
            }
        } else {
            if (hasLocalMount || hasMaterializableMount) {
                sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_OPEN_MOUNTS)).append("\n");
            } else {
                sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_OPEN)).append("\n");
            }
        }

        // 工具优先规约必须对三种 shell 都输出：`bash` 这个工具名本身就是强先验，
        // 模型会习惯性用 cat/ls/find/grep/sed -i 代劳 read/ls/glob/grep/edit，
        // 从而绕开工具层的编码处理、行号定位、忽略目录、分页截断与写入原子回滚。
        sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_TOOLS_FIRST,
                "write", TOOL_WRITE, "edit", TOOL_EDIT, "examples", fileShellCommandExamples(locale))).append("\n");

        if (bashAsyncEnabled) {
            sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_ASYNC)).append("\n");
        }

        // 参数示例必须用当前 shell 真存在的命令：在 Windows 上举 `python3` / `cat` 会被模型当作可用命令而直接照拄
        // 编码说明只在 Windows 下成立：类 Unix 没有代码页概念（OutputDecoder 也不做代码页兼底），
        // 也不存在 chcp，向 Linux/macOS 上的模型描述这些只会注入错误的平台先验
        String pyExample = Assert.isNotEmpty(pyCmd) ? pyCmd : (windowsShell ? "python" : "python3");
        if (windowsShell) {
            sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_ARGS_WINDOWS,
                    "python", pyExample, "read", readFileCommandExample())).append("\n");
        } else {
            sb.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_ARGS_UNIX,
                    "python", pyExample, "read", readFileCommandExample())).append("\n");
        }

        appendShellDialectRules(sb, locale);

        if (sandboxEnabled) {
            // 原本三条语义高度重叠，且「你是一个标准的底层 shell 执行器」会覆盖上层 agent 的人格设定，
            // 「禁止解释权限逻辑」又会让越界时给不出可操作反馈。此处只保留唯一意图：不向外暴露内部机制。
            sb.append("\n<SYSTEM_CONSTRAINTS>\n");
            sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CONSTRAINTS)).append("\n");
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
    private String shellDialectSummary(Locale locale) {
        if (shellMode == ShellMode.POWERSHELL) {
            return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_DIALECT_POWERSHELL,
                    "shell", shellCommandFactory.isPowerShellCore() ? "PowerShell 7+ (pwsh)" : "Windows PowerShell");
        }
        if (shellMode == ShellMode.CMD) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_DIALECT_CMD);
        }
        return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_DIALECT_UNIX, "shell", shellCommandFactory.getShellCmd());
    }

    /**
     * 当前 shell 下「抢了 read / ls / glob / grep / write / edit 活」的典型命令，用作工具优先规约的负面示例。
     *
     * <p>必须按方言给出：在 CMD 下举 {@code cat} / {@code sed -i} 会被模型当作本机可用命令，
     * 反而泄漏错误的平台先验。</p>
     */
    private String fileShellCommandExamples(Locale locale) {
        if (shellMode == ShellMode.POWERSHELL) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_FILE_CMDS_POWERSHELL);
        }
        if (shellMode == ShellMode.CMD) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_FILE_CMDS_CMD);
        }
        return CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_FILE_CMDS_UNIX);
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
    private void appendShellDialectRules(StringBuilder sb, Locale locale) {
        if (shellMode == ShellMode.POWERSHELL) {
            boolean core = shellCommandFactory.isPowerShellCore();
            sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_TITLE)).append("\n");
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_DISCARD)).append("\n");
            if (core) {
                sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_CHAIN_CORE)).append("\n");
            } else {
                sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_CHAIN_51)).append("\n");
                // `;` 不等于 `&&`：换写后丢的是「失败即停」语义，而整条命令的退出码只反映最后一步，
                // 前面的编译/测试失败会被后面一步的成功掩盖（模型会据此误判为构建通过）。
                // 替代写法一律不用 `exit`：上面的「严禁指令」已禁止 exit，且 validateCommandNoKill 会
                // 按 `(^|[;|&])\s*exit\b` 拦截，示范带 exit 的写法会让模型照抄后撞上自家护栏。
                sb.append("    ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_CHAIN_51_NOTE)).append("\n");
            }
            // 「命令不存在」类映射（head/cat/grep/find/uname/pwd/which/export）模型看到
            // CommandNotFoundException 一轮即可自纠，故压成一行速查表而非逐条展开：既省常驻 token，
            // 又不丢信息。真正不可自纠的（别名参数陷阱、2>&1、`;` 语义）仍单独成条。
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_EQUIVALENTS)).append("\n");
            // 「无 Unix 路径」一条已由「终端类型」里的「**不是** bash/sh，POSIX 命令与语法一律不可用」覆盖，
            // `\` 分隔符与 $env:TEMP 属常识，删掉以免同一件事在一份提示里说第三遍。
            appendPowerShellAliasTrapRule(sb, core, locale);
            appendMergedStderrRule(sb, locale);
            appendEncodingRules(sb, core, locale);
        } else if (shellMode == ShellMode.CMD) {
            sb.append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_TITLE)).append("\n");
            // 同 PowerShell 分支：`2>nul` 由下方 stderr 规约给出，「无 Unix 路径」由「不是 bash/sh」覆盖，此处不再重复。
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_EQUIVALENTS)).append("\n");
            // 跨盘符切目录是 CMD 特有的陷：`cd D:\x` 不报错、也不切，后续命令全在错的目录里执行
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_CD)).append("\n");
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_CHAIN)).append("\n");
            appendMergedStderrRule(sb, locale);
            // 多行命令会以批处理脚本形式执行，% 语义与单行命令不同，需向模型说明
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_PERCENT)).append("\n");
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_CMD_GARBLED)).append("\n");
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
    private void appendPowerShellAliasTrapRule(StringBuilder sb, boolean powerShellCore, Locale locale) {
        sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_ALIAS_TRAP)).append("\n");
        if (powerShellCore) {
            sb.append("    ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_CURL_CORE)).append("\n");
        } else {
            sb.append("    ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_CURL_51)).append("\n");
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
    private void appendMergedStderrRule(StringBuilder sb, Locale locale) {
        if (shellMode == ShellMode.POWERSHELL) {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_STDERR_POWERSHELL)).append("\n");
        } else {
            sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_STDERR_CMD)).append("\n");
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
    private void appendEncodingRules(StringBuilder sb, boolean powerShellCore, Locale locale) {
        sb.append("  ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_ENCODING)).append("\n");
        sb.append("    ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_ENCODING_GBK)).append("\n");
        if (powerShellCore == false) {
            sb.append("    ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_ENCODING_BOM)).append("\n");
        }
        sb.append("    ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_INSTRUCTION_PS_ENCODING_GARBLED)).append("\n");
    }


    /**
     * 异步会话工具名称集合，用于过滤
     */
    private static final Set<String> ASYNC_BASH_TOOLS = new HashSet<>(Arrays.asList(
            "bash_start", "bash_wait", "bash_stop"
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
    /** 执行命令（默认语言） */
    public String bash(String command, Integer timeout, Integer maxOutputChars, String __cwd) {
        return bash(command, timeout, maxOutputChars, __cwd, null).getContent();
    }

    /**
     * 执行命令
     *
     * <p>拒绝执行、超时、未能启动或未正常结束返回 {@link ToolResult#error(String)}，
     * 让宿主侧（{@code ToolCallEndEvent#isError()}）不必解析文案就能判断成败。</p>
     *
     * <p>进程正常结束即算成功，不论退出码：退出码的含义因命令而异（grep 未匹配、diff 有差异都返回 1），
     * 由文本中的 {@code [exit_code=N]} 告诉模型自行判断。</p>
     */
    @ToolMapping(
            name = "bash",
            description = "在当前终端执行非交互式命令。工具名叫 bash 仅为历史兼容，**实际 shell 未必是 bash**：请以系统提示中「Terminal 环境状态 → 终端类型」为准（可为 PowerShell / CMD / Unix shell），并按对应方言书写命令。支持逻辑路径（如 `cd @pool1/bin/tool/`）。多步逻辑可把多行脚本整段作为 command 传入，系统会按当前 shell 选择可靠的执行方案（必要时自动写入系统临时目录并在结束后删除）。含空格或特殊字符的参数请用引号包裹；输出编码由系统自动识别。"
    )
    public ToolResult bash(@Param(value = "command", description = "要执行的指令。") String command,
                           @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒") Integer timeout,
                           @Param(name = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符输出，超出保留首尾片段。读取大文件请改用 read 工具。") Integer maxOutputChars,
                           String __cwd,
                           String __locale) {
        Locale locale = AiI18n.toLocale(__locale);

        // 统一安全校验（替代原来的内联检查）
        String violation = support.validateCommandNoKill(command, locale);
        if (violation != null) return ToolResult.error(violation);

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

        // 进程正常结束即成功（不论退出码，退出码由文本中的 [exit_code=N] 告知模型）；
        // 只有超时、未能启动或未正常结束才标记为错误
        CommandResult result = runCommand(command, workPath, envs, timeout, maxOutputChars, locale);
        return result.getStatus() == CommandResult.Status.COMPLETED
                ? ToolResult.success(result.getText())
                : ToolResult.error(result.getText());
    }

    /**
     * 程序侧执行命令（不是模型工具）：与 bash 工具共用同一条执行链——逻辑路径翻译、
     * OS 沙盒包装（包装失败即拒绝执行，不裸跑）、超时整树终止、输出上限。
     *
     * <p>用于 harness 自身需要跑命令的场景（如 goal 的构建/测试验证器），避免各自 {@code ProcessBuilder}
     * 绕开沙盒、无超时。</p>
     *
     * @param command        命令
     * @param cwd            工作目录（与工具的 __cwd 同口径；null = 工作区）
     * @param timeoutMs      超时（毫秒，必须 > 0）
     * @param maxOutputChars 返回文本上限
     * @since 4.2.0
     */
    public CommandResult exec(String command, @Nullable String cwd, int timeoutMs, int maxOutputChars) {
        return exec(command, cwd, timeoutMs, maxOutputChars, null);
    }

    /**
     * 程序侧执行命令（提示文案按指定语言；null 为默认语言）
     *
     * @since 4.2
     */
    public CommandResult exec(String command, @Nullable String cwd, int timeoutMs, int maxOutputChars, @Nullable Locale locale) {
        if (Assert.isEmpty(command)) {
            return CommandResult.failed(CliI18n.get(locale, CliI18nKeys.TERMINAL_EXEC_EMPTY));
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0: " + timeoutMs);
        }

        Path workPath;
        try {
            workPath = getWorkPath(cwd);
        } catch (SecurityException ex) {
            return CommandResult.failed(errorOf(ex, locale));
        }
        return runCommand(command, workPath, new HashMap<>(), timeoutMs, maxOutputChars, locale);
    }

    /**
     * bash 工具与 {@link #exec} 共用的执行链：逻辑路径翻译 → OS 沙盒包装 → 按 shell 方言执行。
     */
    private CommandResult runCommand(String command, Path workPath, Map<String, String> envs,
                                     Integer timeoutMs, Integer maxOutputChars, Locale locale) {
        String finalCommand;
        try {
            finalCommand = support.translateCommandToEnv(command, envs, sandboxEnabled, sandboxAllowUserHome);
        } catch (SecurityException | MountMaterializer.MaterializationException ex) {
            return CommandResult.failed(errorOf(ex, locale));
        }

        // OS 级沙盒包装（内核级强制隔离：Seatbelt / bwrap）
        // 仅当 sandboxSystemRestrict=true 时启用，将安全隔离的重活交给 OS 内核。
        // 显式要求强制隔离时，初始化或包装失败必须拒绝执行，不能降级成裸命令。
        try {
            ensureSandboxInitialized();
            if (sandboxEnabled && sandboxSystemRestrict) {
                if (!SandboxManager.isSandboxingEnabled()) {
                    return CommandResult.failed(sandboxDisabledText(locale));
                }
                finalCommand = SandboxManager.wrapWithSandbox(finalCommand, null, buildDynamicCustomConfig(workPath));
            }
        } catch (Exception e) {
            return CommandResult.failed(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_SANDBOX_UNAVAILABLE, "error", e.getMessage()));
        }

        // 与 bash_start 共用 ShellCommandFactory：Unix 直接 shell -lc 执行；Windows 改走 prepare
        // （PowerShell 用 -EncodedCommand 避开命令文本代码页转换；CMD 默认 /d /c 直连，仅多行/非 ANSI/超长命令才落 .bat）
        if (shellCommandFactory.isWindowsShell()) {
            ShellCommandFactory.PreparedCommand prepared;
            try {
                prepared = shellCommandFactory.prepare(finalCommand);
            } catch (IOException ex) {
                return CommandResult.failed(windowsPrepareFailedText(locale, ex));
            }
            if (prepared != null) {
                try {
                    return executor.executeCmdResult(workPath, prepared.argv(), envs, timeoutMs, maxOutputChars, null, locale);
                } finally {
                    prepared.cleanup();
                }
            }
        }
        return executor.executeCmdResult(workPath, shellCommandFactory.build(finalCommand), envs, timeoutMs, maxOutputChars, null, locale);
    }

    /**
     * 「错误：」+ 异常消息（带文案 key 的异常按语言渲染）
     */
    private static String errorOf(Exception ex, Locale locale) {
        return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_ERROR, "message", CliI18n.messageOf(ex, locale));
    }

    /** OS 沙盒未就绪时的拒绝文案（包内可见：该分支只在初始化竞态下出现，供测试直接校验文案） */
    static String sandboxDisabledText(Locale locale) {
        return CliI18n.get(locale, CliI18nKeys.TERMINAL_SANDBOX_DISABLED);
    }

    /** 无法准备 Windows 执行方案时的文案（包内可见：非 Windows 环境触达不到，供测试直接校验文案） */
    static String windowsPrepareFailedText(Locale locale, IOException ex) {
        return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_WINDOWS_PREPARE_FAILED, "error", ex.getMessage());
    }

    /** 启动命令会话（默认语言） */
    public String bashStart(String command, String workdir, Integer yieldTimeMs, Integer maxOutputChars,
                            Integer hardTimeoutMs, String __cwd) throws IOException {
        return bashStart(command, workdir, yieldTimeMs, maxOutputChars, hardTimeoutMs, __cwd, null).getContent();
    }

    /**
     * 启动命令会话
     *
     * <p>拒绝执行、沙盒不可用、被硬超时终止返回 {@link ToolResult#error(String)}；
     * 仍在运行或正常结束（不论退出码）为成功，与 {@link #bash} 同口径。</p>
     */
    @ToolMapping(
            name = "bash_start",
            description = "启动 shell 命令会话。命令超过 yield_time_ms 仍未结束时不会失败，而是返回 session_id，后续可用 bash_wait 继续等待或输入（chars 参数）、bash_stop 终止。")
    public ToolResult bashStart(@Param(value = "command", description = "要执行的 shell 命令。") String command,
                            @Param(value = "workdir", required = false, description = "工作目录。默认使用当前工作区。") String workdir,
                            @Param(value = "yield_time_ms", required = false, defaultValue = "1000", description = "先等待多久再把控制权交还给模型，单位毫秒。") Integer yieldTimeMs,
                            @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符输出，超出保留最新部分。") Integer maxOutputChars,
                            @Param(value = "hard_timeout_ms", required = false, defaultValue = "120000", description = "硬超时兜底，超过后终止进程树，单位毫秒。") Integer hardTimeoutMs,
                            String __cwd,
                            String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        String danger = support.validateCommandNoKill(command, locale);
        if (danger != null) {
            return ToolResult.error(danger);
        }

        Path workPath = getWorkPath(__cwd);
        Path targetWorkPath;
        try {
            targetWorkPath = support.resolveCommandWorkPath(workPath, workdir, sandboxEnabled, sandboxAllowUserHome);
        } catch (CliSecurityException e) {
            throw e.withLocale(locale);
        }
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
        } catch (SecurityException | MountMaterializer.MaterializationException ex) {
            return ToolResult.error(errorOf(ex, locale));
        }

        // OS 级沙盒包装（内核级强制隔离：Seatbelt / bwrap）
        // 仅当 sandboxSystemRestrict=true 时启用，将安全隔离的重活交给 OS 内核。
        // 显式要求强制隔离时，初始化或包装失败必须拒绝执行，不能降级成裸命令。
        try {
            ensureSandboxInitialized();
            if (sandboxEnabled && sandboxSystemRestrict) {
                if (!SandboxManager.isSandboxingEnabled()) {
                    return ToolResult.error(sandboxDisabledText(locale));
                }
                finalCommand = SandboxManager.wrapWithSandbox(
                        finalCommand, null, buildDynamicCustomConfig(workPath));
            }
        } catch (Exception e) {
            return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_SANDBOX_UNAVAILABLE, "error", e.getMessage()));
        }

        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.exec(finalCommand, targetWorkPath, envs, yieldTimeMs, maxOutputChars, hardTimeoutMs);
        return snapshotResult(snapshot);
    }

    /** 继续等待命令会话（4.1 签名） */
    public String bashWait(String sessionId, String chars, Integer yieldTimeMs, Integer maxOutputChars) throws IOException {
        return bashWait(sessionId, chars, yieldTimeMs, maxOutputChars, null).getContent();
    }

    /**
     * 继续等待命令会话
     *
     * <p>被硬超时终止返回 {@link ToolResult#error(String)}；会话不存在等情况直接抛异常（框架按失败处理）。
     * 输出是固定格式的会话快照，暂不随语言变化；末尾的 {@code __locale} 用于与 4.1 的 String 签名区分，
     * 并与其它工具方法保持一致。</p>
     */
    @ToolMapping(
            name = "bash_wait",
            description = "继续等待仍在运行的命令会话，返回自上次读取后的新增输出或最终状态；也可通过 chars 向进程写入 stdin（应答确认、退出分页器等），不传时仅等待。")
    public ToolResult bashWait(@Param(value = "session_id", description = "bash_start 返回的命令会话 id。") String sessionId,
                               @Param(value = "chars", required = false, description = "可选。要写入 stdin 的文本；不传表示只等待新增输出。") String chars,
                               @Param(value = "yield_time_ms", required = false, defaultValue = "1000", description = "等待新增输出或进程结束的时长，单位毫秒。") Integer yieldTimeMs,
                               @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "本次最多返回多少字符新增输出，超出保留最新部分。") Integer maxOutputChars,
                               String __locale) throws IOException {
        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.writeStdin(sessionId, chars, yieldTimeMs, maxOutputChars);
        return snapshotResult(snapshot);
    }

    /**
     * 会话快照转工具结果：被硬超时终止算失败（与 bash 的超时同口径）；仍在运行、正常结束（不论退出码）算成功。
     */
    private ToolResult snapshotResult(TerminalSessionManager.CommandSnapshot snapshot) {
        String text = formatCommandSnapshot(snapshot);
        return snapshot.timedOut() ? ToolResult.error(text) : ToolResult.success(text);
    }

    /**
     * 终止命令会话
     *
     * <p>保持返回 String：终止正是本工具要做的事，结果恒为成功；会话不存在时直接抛异常（框架按失败处理）。</p>
     */
    @ToolMapping(name = "bash_stop", description = "终止仍在运行的命令会话及其子进程树。")
    public String bashStop(@Param(value = "session_id", description = "bash_start 返回的命令会话 id。") String sessionId,
                           @Param(value = "reason", required = false, description = "终止原因，便于日志诊断。") String reason,
                           @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "终止后最多返回多少字符新增输出。") Integer maxOutputChars) {
        TerminalSessionManager.CommandSnapshot snapshot =
                bashSessionManager.terminate(sessionId, reason, maxOutputChars);
        return formatCommandSnapshot(snapshot);
    }

    // --- 2. 发现文件 ---
    /** 列出目录（默认语言） */
    public String ls(String path, Boolean recursive, Boolean showHidden, String __cwd) throws IOException {
        return ls(path, recursive, showHidden, __cwd, null).getContent();
    }

    /**
     * 列出目录
     *
     * <p>路径不存在返回 {@link ToolResult#error(String)}；空目录算成功。越界等权限拒绝以异常抛出（框架按失败处理）。</p>
     */
    @ToolMapping(name = "ls", description = "列出目录内容。支持递归 Tree 结构展示。支持逻辑路径（如 @pool）。")
    public ToolResult ls(@Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                     @Param(value = "recursive", required = false, description = "是否递归展示") Boolean recursive,
                     @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件") Boolean showHidden,
                     String __cwd,
                     String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        return localized(locale, () -> lsInternal(path, recursive, showHidden, __cwd, locale));
    }

    private ToolResult lsInternal(String path, Boolean recursive, Boolean showHidden, String __cwd, Locale locale) throws IOException {
        Path workPath = getWorkPath(__cwd);
        ResolvedResource virtual = resolveVirtualResource(path, false);
        if (virtual != null) {
            return lsVirtual(virtual, Boolean.TRUE.equals(recursive), Boolean.TRUE.equals(showHidden), locale);
        }
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, fs());

        if (!Files.exists(target)) {
            return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_PATH_MISSING));
        }

        if (Boolean.TRUE.equals(recursive)) {
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");
            support.generateTreeInternal(support.getSandboxPolicyRoot(workPath, path), target, 0, 3, "", sb, Boolean.TRUE.equals(showHidden), sandboxEnabled, fs(), locale);
            return ToolResult.success(sb.toString());
        } else {
            return ToolResult.success(support.flatListLogic(workPath, support.getSandboxPolicyRoot(workPath, path), target, path, Boolean.TRUE.equals(showHidden), sandboxEnabled, fs(), locale));
        }
    }

    /**
     * 文件工具的公共外壳：向上抛出的权限异常绑定请求语言
     */
    private static ToolResult localized(Locale locale, ResultCall call) throws IOException {
        try {
            return call.call();
        } catch (CliSecurityException e) {
            throw e.withLocale(locale);
        }
    }

    @FunctionalInterface
    private interface ResultCall {
        ToolResult call() throws IOException;
    }

    // --- 3. 读取内容 ---
    /** 读取文件（默认语言） */
    public String read(String filePath, Integer offset, Integer limit, String __cwd) throws IOException {
        return read(filePath, offset, limit, __cwd, null).getContent();
    }

    /**
     * 读取文件
     *
     * <p>文件不存在、是目录、是二进制、超过虚拟文件上限、起始行越界返回 {@link ToolResult#error(String)}；
     * 空文件算成功。</p>
     */
    @ToolMapping(name = "read", description = "读取文件内容。修改文件前先通过此工具确认最新文本、缩进和换行符。默认一次性加载完整内容；仅当文件极长或需定位局部时，才使用 offset/limit 分页。支持逻辑路径（如 @pool）。")
    public ToolResult read(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "offset", required = false, description = "（可选）开始读取的行号（默认从1开始索引）") Integer offset,
                       @Param(value = "limit", required = false, description = "（可选）需要读取的最大行数。不传表示完整读取。注意：单次读取受最大物理长度保护，如果触发截断，请根据输出提示调整 offset 分页读取。") Integer limit,
                       String __cwd,
                       String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        return localized(locale, () -> readInternal(filePath, offset, limit, __cwd, locale));
    }

    private ToolResult readInternal(String filePath, Integer offset, Integer limit, String __cwd, Locale locale) throws IOException {
        Path workPath = getWorkPath(__cwd);
        ResolvedResource virtual = resolveVirtualResource(filePath, false);
        if (virtual != null) {
            return readVirtual(virtual, offset, limit, locale);
        }
        Path target = support.resolveSafePath(workPath, filePath, false, sandboxEnabled, sandboxAllowUserHome, fs());
        if (!Files.exists(target)) {
            return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_MISSING));
        }

        if (support.isNotTextFile(target)) {
            return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_BINARY));
        }

        long fileSize = Files.size(target);
        if (fileSize == 0) {
            return ToolResult.success(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_EMPTY));
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
                                .append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_LINE_TRUNCATED)).append("\n");
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
            return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_READ_OFFSET_OUT, "line", startLine0 + 1));
        }

        // 3. 组装最终结果
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- File: %s (Lines: %d - %d, Size: %.2f KB) ---\n",
                filePath, startLine0 + 1, actualEndLine, fileSize / 1024.0));
        sb.append("--------------------------------------------------\n");
        sb.append(contentBuilder);

        // 4. 动态提示与分页引导
        if (isByteTruncated || (limit != null && hasMore)) {
            sb.append("\n\n").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_MORE));
            if (isByteTruncated) {
                sb.append("\n").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_READ_CHAR_LIMIT, "kb", support.maxCharacterLimit / 1024));
            } else {
                sb.append("\n").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_LINE_LIMIT));
            }
            // 给出明确的下一页指令，方便 AI 直接调用
            sb.append("\n").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_READ_NEXT, "offset", actualEndLine + 1));
            if (limit != null) {
                sb.append(", limit=").append(limit);
            }
        } else if (!hasMore) {
            sb.append("\n\n").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_DONE));
        }

        //读完顺手预热语言服务器（异步、不阻塞、失败无声）：模型读过的文件往往紧接着就要改
        warmupFile(target);

        return ToolResult.success(sb.toString());
    }

    // --- 4. 写入与编辑 ---
    // 「不用于临时脚本」写在此处而非 bash 描述里：误用的决策点发生在调用 write 的那一刻。
    // 沙盒下本工具只能写工作区，产出的脚本会进 git 状态且无人清理；多行脚本交给 bash 更省事。
    /** 写入文件（默认语言） */
    public String write(String filePath, String content, String __cwd) throws IOException {
        return write(filePath, content, __cwd, null).getContent();
    }

    /**
     * 写入文件
     *
     * <p>写虚拟文件失败（越界、只读挂载等）时返回 {@link ToolResult#error(String)}，
     * 与 {@link #edit} 的成败语义保持一致。</p>
     */
    @ToolMapping(name = TOOL_WRITE, description = "创建新文件或覆盖现有文件。仅用于项目产物；要执行的多行脚本请直接交给 `bash`，不必先落成临时脚本文件。")
    public ToolResult write(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                            @Param(value = PARAM_CONTENT, description = "完整文本内容。") String content,
                            String __cwd,
                            String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        return localized(locale, () -> writeInternal(filePath, content, __cwd, locale));
    }

    private ToolResult writeInternal(String filePath, String content, String __cwd, Locale locale) throws IOException {
        Path workPath = getWorkPath(__cwd);
        ResolvedResource virtual = resolveVirtualResource(filePath, true);
        if (virtual != null) {
            return writeVirtual(virtual, content, locale);
        }
        Path target = support.resolveSafePath(workPath, filePath, true, sandboxEnabled, sandboxAllowUserHome, fs());

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return ToolResult.success(withDiagnostics(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_WRITE_OK, "path", filePath), target));
    }


    /** 编辑文件（默认语言；失败时以文本返回，不区分错误标记） */
    public String edit(String filePath, List<EditOp> edits, String __cwd) throws IOException {
        return edit(filePath, edits, __cwd, null).getContent();
    }

    /**
     * 编辑文件
     *
     * <p>失败时返回 {@link ToolResult#error(String)}，让宿主侧（{@code ToolCallEndEvent#isError()}）
     * 不必解析文案就能判断成败。</p>
     */
    @ToolMapping(
            name = TOOL_EDIT,
            description = "对文件进行文本替换，自动忽略行首行尾空白差异。支持单次调用执行一处或多处编辑。具有原子性：所有编辑成功才会写入，否则全部回滚。"
    )
    public ToolResult edit(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                           @Param(value = PARAM_EDITS, description = "编辑操作列表") List<EditOp> edits,
                           String __cwd,
                           String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        return localized(locale, () -> editInternal(filePath, edits, __cwd, locale));
    }

    /** 编辑操作的编号（如 "#1"） */
    private static String opNo(int index) {
        return "#" + (index + 1);
    }

    private ToolResult editInternal(String filePath, List<EditOp> edits, String __cwd, Locale locale) throws IOException {
        Path workPath = getWorkPath(__cwd);
        ResolvedResource virtual = resolveVirtualResource(filePath, true);
        if (virtual != null) {
            return editVirtual(virtual, edits, locale);
        }
        Path target = support.resolveSafePath(workPath, filePath, true, sandboxEnabled, sandboxAllowUserHome, fs());

        if (!Files.exists(target)) {
            return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_EDIT_MISSING));
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
                return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_EMPTY_OLD, "op", opNo(i)));
            }

            if (edit.newStr == null) {
                edit.newStr = "";
            }

            String finalOld = support.normalizeNewlines(originalContent, edit.oldStr);

            if (Boolean.TRUE.equals(edit.replaceAll)) {
                if (!originalContent.contains(finalOld)) {
                    return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_ALL_MISSING, "op", opNo(i)));
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
                // old_StrStartLine 可缺省（模型常不带）：缺省时按「无行号提示」全篇诊断
                int hintLine = (edit.oldStrStartLine == null) ? 0 : edit.oldStrStartLine;
                String diag = support.findLooseMatchDiagnostics(originalContent, finalOld, hintLine, locale);
                if (diag != null) {
                    return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_MISMATCH, "op", opNo(i), "diag", diag));
                }
                // diag 返回 null 表示宽松匹配成功（边缘情况），允许通过预检查
                continue;
            }

            if (originalContent.lastIndexOf(finalOld) != firstIndex) {
                return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_NOT_UNIQUE, "op", opNo(i)));
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
                return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_APPLY_FAILED, "op", opNo(editIndex), "error", CliI18n.messageOf(e, locale)));
            }
        }

        // 原子性保存（原文件带 BOM 则保持带 BOM）
        Files.write(target, (hadBom ? TerminalSupport.UTF8_BOM + workingContent : workingContent).getBytes(fileCharset));

        return ToolResult.success(withDiagnostics(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_OK, "path", filePath, "count", edits.size()), target));
    }

    // --- 5. 搜索工具 ---
    /** 搜索内容（默认语言） */
    public String grep(String pattern, String path, String include, String __cwd) throws IOException {
        return grep(pattern, path, include, __cwd, null).getContent();
    }

    /**
     * 搜索内容
     *
     * <p>搜索路径不存在或传了多个路径返回 {@link ToolResult#error(String)}；未找到结果算成功。</p>
     */
    @ToolMapping(name = "grep", description = "递归搜索内容。返回 '路径:行号:内容'。在不确定文件位置时先执行搜索。支持逻辑路径（如 @pool）。pattern 支持正则表达式匹配。")
    public ToolResult grep(@Param(value = "pattern", description = "搜索内容，支持正则表达式匹配") String pattern,
                       @Param(value = "path", description = "（单个）目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       @Param(value = "include", required = false, description = "要包含的文件模式（如 \"*.js\"、\"*.{ts,tsx}\"）") String include,
                       String __cwd,
                       String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        return localized(locale, () -> grepInternal(pattern, path, include, __cwd, locale));
    }

    private ToolResult grepInternal(String pattern, String path, String include, String __cwd, Locale locale) throws IOException {
        Path workPath = getWorkPath(__cwd);
        ResolvedResource virtual = resolveVirtualResource(path, false);
        if (virtual != null) {
            return grepVirtual(virtual, pattern, include, locale);
        }
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, fs());

        String pathError = checkSearchPath("grep", path, target, locale);
        if (pathError != null) {
            return ToolResult.error(pathError);
        }

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
            sb.append("\n\n").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_MORE));
            sb.append("\n").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_GREP_TRUNCATED, "kb", support.maxCharacterLimit / 1024));
        }

        return ToolResult.success(sb.length() == 0 ? CliI18n.get(locale, CliI18nKeys.TERMINAL_GREP_NONE) : sb.toString());
    }

    /** 按模式搜索文件（默认语言） */
    public String glob(String pattern, String path, String __cwd) throws IOException {
        return glob(pattern, path, __cwd, null).getContent();
    }

    /**
     * 按模式搜索文件
     *
     * <p>搜索路径不存在或传了多个路径返回 {@link ToolResult#error(String)}；未找到匹配文件算成功。</p>
     */
    @ToolMapping(name = "glob", description = "按通配符模式（如 **/*.java）搜索文件。确定文件范围的最高效工具。支持逻辑路径（如 @pool）。")
    public ToolResult glob(@Param(value = "pattern", description = "glob 模式。") String pattern,
                       @Param(value = "path", description = "（单个）目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       String __cwd,
                       String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        return localized(locale, () -> globInternal(pattern, path, __cwd, locale));
    }

    private ToolResult globInternal(String pattern, String path, String __cwd, Locale locale) throws IOException {
        Path workPath = getWorkPath(__cwd);
        ResolvedResource virtual = resolveVirtualResource(path, false);
        if (virtual != null) {
            return globVirtual(virtual, pattern, locale);
        }
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, sandboxAllowUserHome, fs());

        String pathError = checkSearchPath("glob", path, target, locale);
        if (pathError != null) {
            return ToolResult.error(pathError);
        }

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
        if (results.isEmpty()) return ToolResult.success(CliI18n.get(locale, CliI18nKeys.TERMINAL_GLOB_NONE));
        Collections.sort(results);
        return ToolResult.success(String.join("\n", results));
    }

    /** 解析仅能通过 MountSource 访问的虚拟来源；本地来源继续走既有 Path 安全路径。 */
    private ResolvedResource resolveVirtualResource(String path, boolean write) throws IOException {
        if (path == null || !path.startsWith("@")) return null;
        ResolvedResource resource = mountManager.resolveResource(path);
        // 本地路径能力只决定是否可以走 Path/Files 快路径，不决定 MountSource 是否能被文件工具使用。
        // 未声明 localPathAccessible 的来源，即使偶然提供了本地映射，也必须保留其自身的
        // stat/list/find/openRead/openWrite 实现，避免把文件工具错误地绑定到本地文件系统。
        if (resource.getSource().capabilities().isLocalPathAccessible()
                && resource.getLocalPath().isPresent()) {
            return null;
        }
        Mount mount = resource.getMount();
        if (mount == null || !mount.isEnabled()) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_MOUNT_UNAVAILABLE, "path", path);
        }
        MountSource source = resource.getSource();
        if (!source.capabilities().isReadable()) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_UNREADABLE, "path", path);
        }
        if (write && (!mount.isWritable() || !source.capabilities().isWritable())) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_READONLY_SOURCE, "path", path);
        }
        return resource;
    }

    private ToolResult lsVirtual(ResolvedResource resource, boolean recursive, boolean showHidden, Locale locale) throws IOException {
        MountSource source = resource.getSource();
        String base = resource.getSourcePath();
        if (source.stat(base) == null) return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_PATH_MISSING));
        List<MountEntry> entries = recursive
                ? source.find(base, FindOptions.builder().maxDepth(3).maxEntries(500).build())
                : source.list(base);
        List<String> lines = new ArrayList<>();
        for (MountEntry entry : entries) {
            String name = entry.getName();
            if (!showHidden && name.startsWith(".")) continue;
            String display = resource.getMountAlias() + "/" + entry.getPath();
            lines.add((entry.isDirectory() ? "[DIR] " : "[FILE] ") + display + (entry.isDirectory() ? "/" : ""));
        }
        Collections.sort(lines);
        return ToolResult.success(lines.isEmpty() ? CliI18n.get(locale, CliI18nKeys.TERMINAL_LS_EMPTY) : String.join("\n", lines));
    }

    private ToolResult readVirtual(ResolvedResource resource, Integer offset, Integer limit, Locale locale) throws IOException {
        MountEntry entry = resource.getSource().stat(resource.getSourcePath());
        if (entry == null) return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_MISSING));
        if (entry.isDirectory()) return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_DIR));
        if (entry.getSize() > MAX_VIRTUAL_FILE_BYTES) return ToolResult.error(virtualFileTooLarge(locale));
        byte[] bytes;
        try (InputStream in = resource.getSource().openRead(resource.getSourcePath())) {
            bytes = readVirtualBytes(in);
        } catch (VirtualFileTooLargeException e) {
            return ToolResult.error(virtualFileTooLarge(locale));
        }
        String text = TerminalSupport.stripUtf8Bom(new String(bytes, fileCharset));
        String[] lines = text.split("\\R", -1);
        int start = offset == null || offset < 1 ? 0 : offset - 1;
        int end = limit == null || limit <= 0 ? lines.length : (int) Math.min(lines.length, (long) start + limit);
        if (start >= lines.length) return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_READ_OFFSET_OUT, "line", start + 1));
        StringBuilder out = new StringBuilder();
        int lastLine = start;
        boolean truncated = false;
        for (int i = start; i < end; i++) {
            String line = String.format("%6d | %s\n", i + 1, lines[i]);
            if (out.length() + line.length() > support.maxCharacterLimit) {
                truncated = true;
                if (out.length() == 0) {
                    String prefix = String.format("%6d | ", i + 1);
                    int chars = Math.max(0, Math.min(lines[i].length(), support.maxCharacterLimit - prefix.length() - 16));
                    out.append(prefix).append(lines[i], 0, chars).append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_LINE_TRUNCATED)).append("\n");
                    lastLine = i + 1;
                }
                break;
            }
            out.append(line);
            lastLine = i + 1;
        }
        out.insert(0, String.format("--- File: %s (Lines: %d - %d, Size: %.2f KB) ---\n--------------------------------------------------\n", resource.getLogicalPath(), start + 1, lastLine, bytes.length / 1024.0));
        out.append(truncated || lastLine < lines.length
                ? "\n\n" + CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_MORE) + "\n" + CliI18n.fmt(locale, CliI18nKeys.TERMINAL_READ_NEXT, "offset", lastLine + 1)
                : "\n\n" + CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_DONE));
        return ToolResult.success(out.toString());
    }

    private ToolResult writeVirtual(ResolvedResource resource, String content, Locale locale) throws IOException {
        try (OutputStream out = resource.getSource().openWrite(resource.getSourcePath(), WriteOptions.replace())) {
            out.write(content == null ? new byte[0] : content.getBytes(fileCharset));
        } catch (UnsupportedOperationException | SecurityException e) {
            // 只读挂载等情况：以结构化错误返回，便于宿主区分「写失败」与「写成功」
            return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_ERROR,
                    "message", CliI18n.messageOf(e, locale)));
        }
        return ToolResult.success(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_WRITE_OK, "path", resource.getLogicalPath()));
    }

    private ToolResult editVirtual(ResolvedResource resource, List<EditOp> edits, Locale locale) throws IOException {
        MountEntry entry = resource.getSource().stat(resource.getSourcePath());
        if (entry == null || entry.isDirectory()) return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_EDIT_MISSING));
        if (entry.getSize() > MAX_VIRTUAL_FILE_BYTES) return ToolResult.error(virtualFileTooLarge(locale));
        String raw;
        try (InputStream in = resource.getSource().openRead(resource.getSourcePath())) {
            raw = new String(readVirtualBytes(in), fileCharset);
        } catch (VirtualFileTooLargeException e) {
            return ToolResult.error(virtualFileTooLarge(locale));
        }
        boolean bom = TerminalSupport.hasUtf8Bom(raw);
        String original = TerminalSupport.stripUtf8Bom(raw);
        for (int i = 0; i < edits.size(); i++) {
            EditOp edit = edits.get(i);
            if (edit.oldStr == null || edit.oldStr.isEmpty()) return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_VEDIT_EMPTY_OLD, "op", opNo(i)));
            String old = support.normalizeNewlines(original, edit.oldStr);
            if (!Boolean.TRUE.equals(edit.replaceAll) && original.indexOf(old) < 0) {
                return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_VEDIT_MISMATCH, "op", opNo(i)));
            }
            if (Boolean.TRUE.equals(edit.replaceAll) && !original.contains(old)) {
                return ToolResult.error(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_VEDIT_ALL_MISSING, "op", opNo(i)));
            }
        }
        String updated = original;
        for (Integer index : buildEditExecutionOrder(edits)) {
            EditOp edit = edits.get(index);
            updated = support.applyEditLogic(updated, edit.oldStr, edit.newStr == null ? "" : edit.newStr,
                    Boolean.TRUE.equals(edit.replaceAll), edit.oldStrStartLine);
        }
        WriteOptions options = entry.getVersion() == null
                ? WriteOptions.replace()
                : WriteOptions.builder().expectedVersion(entry.getVersion()).build();
        try (OutputStream out = resource.getSource().openWrite(resource.getSourcePath(), options)) {
            out.write((bom ? TerminalSupport.UTF8_BOM + updated : updated).getBytes(fileCharset));
        }
        return ToolResult.success(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_EDIT_OK, "path", resource.getLogicalPath(), "count", edits.size()));
    }

    private ToolResult grepVirtual(ResolvedResource resource, String pattern, String include, Locale locale) throws IOException {
        FindOptions options = FindOptions.builder().maxDepth(20).maxEntries(500).filesOnly(true).build();
        List<MountEntry> entries;
        MountEntry requested = resource.getSource().stat(resource.getSourcePath());
        if (requested == null) return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_PATH_MISSING));
        if (requested.isDirectory()) {
            entries = resource.getSource().find(resource.getSourcePath(), options);
        } else {
            entries = Collections.singletonList(requested);
        }
        Pattern regex = null;
        try { regex = Pattern.compile(pattern); } catch (PatternSyntaxException ignored) { }
        StringBuilder out = new StringBuilder();
        int skippedLarge = 0;
        for (MountEntry entry : entries) {
            if (include != null && !include.isEmpty() && !globMatches(include, entry.getName())) continue;
            if (entry.getSize() > MAX_VIRTUAL_FILE_BYTES) {
                skippedLarge++;
                continue;
            }
            byte[] bytes;
            try (InputStream in = resource.getSource().openRead(entry.getPath())) {
                bytes = readVirtualBytes(in);
            } catch (VirtualFileTooLargeException e) {
                skippedLarge++;
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new ByteArrayInputStream(bytes), fileCharset))) {
                String line; int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    line = TerminalSupport.stripUtf8Bom(line);
                    if (regex != null ? regex.matcher(line).find() : line.contains(pattern)) {
                        String display = resource.getMountAlias() + "/" + entry.getPath();
                        out.append(display).append(":").append(lineNo).append(": ").append(line.trim()).append('\n');
                        if (out.length() >= support.maxCharacterLimit) return ToolResult.success(out.substring(0, support.maxCharacterLimit));
                    }
                }
            }
        }
        if (skippedLarge > 0) out.append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_GREP_SKIPPED_LARGE, "count", skippedLarge)).append("\n");
        return ToolResult.success(out.length() == 0 ? CliI18n.get(locale, CliI18nKeys.TERMINAL_GREP_NONE) : out.toString());
    }

    private ToolResult globVirtual(ResolvedResource resource, String pattern, Locale locale) throws IOException {
        MountEntry requested = resource.getSource().stat(resource.getSourcePath());
        if (requested == null) return ToolResult.error(CliI18n.get(locale, CliI18nKeys.TERMINAL_PATH_MISSING));
        List<MountEntry> entries;
        if (requested.isDirectory()) {
            entries = resource.getSource().find(resource.getSourcePath(),
                    FindOptions.builder().maxDepth(20).maxEntries(500).filesOnly(true).glob(pattern.replace('\\', '/')).build());
        } else {
            entries = Collections.singletonList(requested);
        }
        List<String> result = new ArrayList<>();
        for (MountEntry entry : entries) result.add("[FILE] " + resource.getMountAlias() + "/" + entry.getPath());
        Collections.sort(result);
        return ToolResult.success(result.isEmpty() ? CliI18n.get(locale, CliI18nKeys.TERMINAL_GLOB_NONE) : String.join("\n", result));
    }

    private boolean globMatches(String pattern, String value) {
        try { return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Paths.get(value)); }
        catch (RuntimeException e) { return value.equals(pattern); }
    }

    private static String virtualFileTooLarge(Locale locale) {
        return CliI18n.get(locale, CliI18nKeys.TERMINAL_VFILE_TOO_LARGE);
    }

    private byte[] readVirtualBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > MAX_VIRTUAL_FILE_BYTES - output.size()) throw new VirtualFileTooLargeException();
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class VirtualFileTooLargeException extends IOException {
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

    /**
     * 校验搜索类工具（grep/glob）的 path 参数。
     * <p>
     * 这类工具只接受「单个已存在的目录」。调用方常按 ripgrep 习惯用 ';' 或 ',' 拼接多个路径，
     * 拼串会被当成一个目录名解析，最终在 walkFileTree 抛 NoSuchFileException，
     * 调用方只能看到一条堆栈、拿不到纠偏线索。此处统一转成可执行的提示文案。
     * <p>
     * 分隔符判定放在存在性判定之后：目录名本身合法地含有 ',' 等字符时不会被误拦。
     *
     * @return 不合法时返回提示文案；合法返回 null
     */
    private String checkSearchPath(String toolName, String path, Path target, Locale locale) {
        if (Files.exists(target)) {
            return null;
        }

        if (path != null) {
            for (char ch : new char[]{';', ',', '\n', '\r', '|'}) {
                if (path.indexOf(ch) >= 0) {
                    String chLabel = (ch == '\n' ? "\\n" : (ch == '\r' ? "\\r" : String.valueOf(ch)));
                    return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_SEARCH_SEPARATOR,
                            "tool", toolName, "sep", chLabel, "path", path);
                }
            }
        }

        return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_SEARCH_MISSING, "tool", toolName, "path", path);
    }

    private Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : mountManager.getWorkDir();
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        Path p = Paths.get(path).toAbsolutePath().normalize();

        // 工作根策略在消费端校验：__cwd 可能来自请求级 toolContext 或被直接写进会话属性，
        // 只在 setSessionCwd 校验挡不住这些路径。工作区根本身可信，不校验。
        Predicate<Path> policy = workRootPolicy;
        if (policy != null && __cwd != null) {
            Path real = p;
            if (Files.exists(p)) {
                try {
                    real = p.toRealPath(); // 防符号链接逃逸
                } catch (IOException e) {
                    throw new SecurityException("Working directory is invalid: " + p);
                }
            }
            if (policy.test(real) == false) {
                throw new SecurityException("Working directory is out of policy: " + p);
            }
            return real;
        }
        return p;
    }

    private volatile Predicate<Path> workRootPolicy;

    /**
     * 工作根策略：工具与 {@link #exec} 使用本轮 {@code __cwd} 之前校验（规范化 + 真实路径），不通过时拒绝执行。
     * null = 不限制。harness 按引擎的 {@code cwdPolicy} 设置。
     *
     * @since 4.2.0
     */
    public void setWorkRootPolicy(@Nullable Predicate<Path> workRootPolicy) {
        this.workRootPolicy = workRootPolicy;
    }

    /**
     * 工作区目录名提示（仅末级目录名，不暴露完整路径）。
     * 供指令中的定位锚点使用；取不到（未设置/根目录/空名）时返回空串，指令退回无名称形态。
     *
     * <p>口径必须与 {@link #getWorkPath(String)} 一致：优先取本轮会话工作目录（prompt 属性 {@code __cwd}），
     * 缺省回落到 MountManager 的工作区根。否则会话 cwd 不在工作区时（如 goal 把会话目录切到目标项目），
     * 引导词宣称的「解析根」与文件工具/bash 实际使用的根不一致。
     */
    String workspaceNameHint(Prompt prompt) {
        return workspaceNameHint(prompt, null);
    }

    String workspaceNameHint(Prompt prompt, Locale locale) {
        String cwd = (prompt == null) ? null : prompt.attrAs(ATTR_CWD);
        String workDir = Assert.isNotEmpty(cwd) ? cwd : mountManager.getWorkDir();
        if (workDir == null || workDir.isEmpty()) {
            return "";
        }
        Path fileName = Paths.get(workDir).toAbsolutePath().normalize().getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        if (name.isEmpty()) {
            return "";
        }
        return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_INSTRUCTION_WORKSPACE_NAME, "name", name);
    }


    private String formatCommandSnapshot(TerminalSessionManager.CommandSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Command Session\n");
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
        sb.append("output_chars_total: ").append(snapshot.outputChars()).append('\n');
        sb.append("output_chars_returned: ").append(snapshot.returnedChars()).append('\n');
        sb.append("output_truncated: ").append(snapshot.outputTruncated()).append('\n');
        if (snapshot.running()) {
            sb.append("Process running with session ID: ").append(snapshot.sessionId()).append('\n');
            sb.append("Use bash_wait to continue waiting (or send input via chars), or bash_stop to stop it.\n");
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
        return validateCommand(command, null);
    }

    /**
     * 命令校验（错误文案按指定语言；null 为默认语言）
     *
     * @since 4.2
     */
    public String validateCommand(String command, Locale locale) {
        // 先做基础安全校验（kill 保护等）
        String violation = support.validateCommandNoKill(command, locale);
        if (violation != null) {
            return violation;
        }
        // sandboxAllowUserHome=false 时，阻止 ~ 路径。
        // 复用生产路径同款判定（containsUserHomePath），避免测试/生产逻辑分裂。
        if (sandboxEnabled && !sandboxAllowUserHome && support.containsUserHomePath(command)) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_VALIDATE_HOME);
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
