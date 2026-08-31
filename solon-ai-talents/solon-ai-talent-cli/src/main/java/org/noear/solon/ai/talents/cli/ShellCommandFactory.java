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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 统一的 shell 命令启动拼装。
 * <p>
 * 同步 {@code bash} 与异步 {@code bash_start} 都应通过这里生成 argv，避免两套启动契约分叉：
 * <ul>
 *   <li>Unix: {@code shell -lc command}</li>
 *   <li>Windows CMD: {@code cmd /c command}</li>
 *   <li>Windows PowerShell: {@code powershell -Command command}</li>
 * </ul>
 *
 * @author noear
 * @since 4.0.4
 */
public final class ShellCommandFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ShellCommandFactory.class);

    /**
     * {@code -EncodedCommand} 的 Base64 长度上限。Windows 命令行总长约 32767 字符，
     * 留足前缀与余量；超限则回退到临时 {@code .ps1} 脚本。
     */
    private static final int MAX_ENCODED_COMMAND_CHARS = 24_000;

    /**
     * {@code cmd /c} 命令行的长度上限（约 8191），留出前缀与余量；超限则落临时 {@code .bat}。
     */
    private static final int MAX_CMD_LINE_CHARS = 7_500;

    /**
     * 探测父 shell 时向上遍历祖先进程的最大层数。覆盖「JVM ← 启动脚本/包装器 ← 真实 shell」
     * 这类多层链，同时避免无谓地一路走到 explorer.exe / services.exe。
     */
    private static final int MAX_PARENT_WALK = 6;

    /**
     * 祖先链探测中需要跳过的前两层：{@code level0} 是探测用的 PowerShell 自身（不跳会自匹配成
     * powershell），{@code level1} 是当前 JVM（等价于 {@code 原实现的 current()}，也不该参与判定）。
     * 因此脚本共走 {@code SKIP + MAX_PARENT_WALK} 层，输出的第一行才对应「JVM 的父进程」。
     */
    private static final int PARENT_WALK_SKIP = 2;

    /**
     * 祖先链探测的超时。实测（Windows 11 + PowerShell 5.1）典型耗时 ≈ 1.2s，其中约 0.6s 是
     * PowerShell 自身的启动成本、约 0.6s 是 WMI 查询；因此超时不能跟 {@code EnvironmentResolver}
     * 的 2s 一样紧（实测会在冷启动/负载高时碍到），也不能无限等（它在 detect() 的同步路径上）。
     */
    private static final long PARENT_PROBE_TIMEOUT_MS = 4_000;

    /**
     * 探测输出的行标记。祖先链探测把 stderr 合并进 stdout（避免未读的 stderr 管道憋住子进程），
     * 而 PowerShell 的报错文本里可能出现 {@code powershell} 字样——若不加标记逐行甄别，
     * 一条错误信息就足以把判定结果带偏。
     */
    private static final String PARENT_PROBE_MARK = "p#";

    /** 父 shell 探测失败/不可判定的缓存哨兵（进程名不可能是 NUL 字符） */
    private static final String PARENT_PROBE_FAILED = "\u0000";

    private static volatile String cachedUnixShell;

    /** 父 shell 探测结果缓存：探测要 fork 进程，而 {@link #detect()} 有多个调用点 */
    private static volatile String cachedWindowsParentShell;

    /**
     * PowerShell 启动前置脚本：把一次执行内的「输出 / 输入 / 文件读写」编码全部归一到 UTF-8。
     *
     * <p><b>为什么不只设 [Console]::OutputEncoding</b>：Windows PowerShell 5.1 里至少有四个
     * 相互独立的编码决策点，只改其中一个仅能消除一部分乱码：
     * <ul>
     *   <li><b>引擎输出流</b>（{@code $OutputEncoding} / {@code [Console]::OutputEncoding}）：决定
     *       PowerShell 向下游管道写什么字节、以及如何解码 native 子命令的 stdout。不设则为 ANSI
     *       代码页，Java 侧按 UTF-8 解码必乱。</li>
     *   <li><b>引擎输入流</b>（{@code [Console]::InputEncoding}）：{@code bash_wait(chars)} 向会话写入的
     *       是 UTF-8 字节，不设则 PowerShell 按 ANSI 读，非 ASCII 应答（如中文确认）会变乱码。</li>
     *   <li><b>文件读取</b>（{@code Get-Content} / {@code Select-String} / {@code Import-Csv} 的
     *       {@code -Encoding} 默认值）：<b>5.1 的默认是 ANSI 代码页，不是 UTF-8</b>。这是最难排查的
     *       一类乱码：乱码发生在 PowerShell <b>内部</b>，它把 UTF-8 文件按 GBK 读成了错字，再把错字
     *       按 UTF-8 正确地写给我们——字节层完全合法，{@link OutputDecoder} 无从下手，只能在源头修。
     *       典型现象：{@code Get-Content README.md} 把「构建」显示成「鎂劫建」。</li>
     *   <li><b>文件写入</b>（{@code Set-Content} / {@code Add-Content} / {@code Out-File}，以及重定向
     *       {@code >} / {@code >>}）：{@code Set-Content} 默认 ANSI（写 emoji / 日韩文直接丢字），
     *       而 {@code >} 默认 <b>UTF-16LE</b>（生成的文件在任何按 UTF-8 读的工具里都是乱码）。
     *       实测 {@code Out-File:Encoding} 的默认值对 {@code >} / {@code >>} 同样生效，
     *       因此这一条就能把重定向产物从 UTF-16LE 纠正为 UTF-8。</li>
     * </ul>
     *
     * <p><b>为什么用白名单而不是 {@code '*:Encoding'}</b>：通配符会波及所有带 {@code -Encoding}
     * 参数的 cmdlet，而其中部分（如 {@code Send-MailMessage}）的该参数类型是
     * {@code System.Text.Encoding} 对象，传字符串 {@code 'UTF8'} 会直接报参数绑定错误。
     *
     * <p><b>已知取舍</b>：
     * <ul>
     *   <li>5.1 的 {@code -Encoding UTF8} 写入带 BOM。相比默认 ANSI「emoji/日韩文直接丢字」
     *       的不可逆损失，BOM 只是一个可识别、可剥除的前缀（本模块的 read/edit/grep 已统一忽略
     *       首部 BOM），因此选择前者。</li>
     *   <li>读取默认改成 UTF-8 后，真正的 GBK 文件需显式 {@code -Encoding Default}。开发场景下
     *       源码/配置/文档绝大多数是 UTF-8，这个默认值的命中率明显更高（引导词里已告知模型
     *       例外写法）。</li>
     * </ul>
     *
     * <p><b>健壮性</b>：{@code UTF8Encoding $false} 而非 {@code [Text.Encoding]::UTF8}——后者带
     * preamble，赋给 {@code [Console]::OutputEncoding} 会让输出开头多出 {@code \uFEFF}。所有赋值
     * 均包 {@code try/catch}：无控制台句柄时这些 setter 会抛 IOException，不能让首行报错混
     * 进合并后的输出流。
     */
    static final String POWERSHELL_PREAMBLE =
            "$OutputEncoding = New-Object System.Text.UTF8Encoding $false\r\n"
                    + "try { [Console]::OutputEncoding = $OutputEncoding } catch { }\r\n"
                    + "try { [Console]::InputEncoding = $OutputEncoding } catch { }\r\n"
                    + "try {\r\n"
                    + "  if ($null -eq $PSDefaultParameterValues) { $PSDefaultParameterValues = @{} }\r\n"
                    + "  foreach ($c in 'Get-Content','Set-Content','Add-Content','Out-File',"
                    + "'Select-String','Import-Csv','Export-Csv') {\r\n"
                    + "    $PSDefaultParameterValues[($c + ':Encoding')] = 'UTF8'\r\n"
                    + "  }\r\n"
                    + "} catch { }\r\n";

    private final ShellMode shellMode;
    private final String shellCmd;

    public ShellCommandFactory(ShellMode shellMode, String shellCmd) {
        this.shellMode = Objects.requireNonNull(shellMode, "shellMode");
        this.shellCmd = requireNonEmpty(shellCmd, "shellCmd");
    }

    /**
     * 显式指定 Windows shell 的系统属性（取值：{@code cmd} / {@code powershell} / {@code pwsh}）。
     *
     * <p><b>为何需要逃生口</b>：自动推定看的是祖先进程链，而 Java CLI 工具极常见的启动方式
     * 是 {@code xxx.bat} / {@code xxx.cmd} 包装脚本——那会让祖先链里真的出现 {@code cmd.exe}，于是被判定为
     * CMD 方言（三种方言里能力最弱、且无法治理编码的一个）。没有覆盖手段的话，用户就因为
     * “用了官方启动脚本”而被锁在中文乱码的 CMD 里。</p>
     */
    static final String SHELL_OVERRIDE_PROPERTY = "solon.ai.cli.shell";
    /** 同 {@link #SHELL_OVERRIDE_PROPERTY} 的环境变量形式（启动脚本里更好写） */
    static final String SHELL_OVERRIDE_ENV = "SOLON_AI_CLI_SHELL";

    /**
     * 按当前 OS 探测默认 shell。
     *
     * <p>Windows 侧不能用 {@code COMSPEC} 判定：该变量是 Windows 的遗留命令解释器路径，
     * 无论调用者是 CMD、PowerShell 还是 Windows Terminal，它都恒为 {@code cmd.exe}，
     * 从不包含 {@code powershell}。改为向上遍历父进程链，按进程名判定真实调用 shell。</p>
     *
     * <p>探测结果可被 {@link #SHELL_OVERRIDE_PROPERTY} / {@link #SHELL_OVERRIDE_ENV} 覆盖（仅 Windows；
     * 类 Unix 下 shell 本身就是 POSIX 兼容的，不存在方言选择问题）。</p>
     */
    public static ShellCommandFactory detect() {
        if (EnvironmentResolver.isWindows()) {
            ShellCommandFactory overridden = windowsShellOverride();
            return overridden != null ? overridden : detectWindowsShell();
        }
        return new ShellCommandFactory(ShellMode.UNIX_SHELL, probeUnixShell());
    }

    /**
     * 读取显式指定的 Windows shell；未配置或取值无法识别时返回 {@code null}（不猜，交回自动探测）。
     */
    static ShellCommandFactory windowsShellOverride() {
        String value = System.getProperty(SHELL_OVERRIDE_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(SHELL_OVERRIDE_ENV);
        }
        if (value == null) {
            return null;
        }
        value = value.trim().toLowerCase();
        if ("cmd".equals(value)) {
            return new ShellCommandFactory(ShellMode.CMD, "cmd");
        }
        if ("pwsh".equals(value) || "powershell-core".equals(value)) {
            return new ShellCommandFactory(ShellMode.POWERSHELL, "pwsh");
        }
        if ("powershell".equals(value)) {
            return new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        }
        return null;
    }

    /**
     * Windows 侧的 shell 方案推定：先按祖先进程名认真实调用者，不可判定时默认 PowerShell。
     *
     * <p><b>为何默认 PowerShell 而不是 CMD</b>：从 IDE（{@code idea64.exe → java.exe}）、
     * 服务、计划任务启动时，祖先链里根本没有 shell，而这是开发场景里极常见的启动方式。
     * 回退到 CMD 意味着拿到三种方言里能力最弱的一个：无 {@code head}/{@code tail} 等价命令、
     * 无结构化管道，而且无法在不破坏 {@code %} 语义的前提下注入 UTF-8 前置（参见
     * {@link #POWERSHELL_PREAMBLE}）——也就是说回退到 CMD 同时也丢掉了编码治理能力。
     * 现代 Windows 一定自带 PowerShell，因此把它作为不可判定时的默认值更合理。</p>
     */
    static ShellCommandFactory detectWindowsShell() {
        return windowsShellOf(detectWindowsParentShellName());
    }

    /**
     * 由「父 shell 可执行名」推定启动方案的纯函数（{@code null} 表示不可判定）。
     *
     * <p>抽成纯函数是为了可测：探测结果依赖测试进程真实的祖先链（surefire fork ← mvn ← 某个 shell），
     * 直接断言 {@link #detectWindowsParentShellName()} 的返回值会随启动方式漂移。</p>
     */
    static ShellCommandFactory windowsShellOf(String parentShellName) {
        if ("cmd".equals(parentShellName)) {
            return new ShellCommandFactory(ShellMode.CMD, "cmd");
        }
        if (parentShellName != null) {
            // 父进程就是 pwsh / powershell：原名启动，不能把 pwsh 7 降级成 5.1
            // （版本不一致会让引导词里的语法能力描述也跟着错，且只装 pwsh 的机器会直接启不来）
            return new ShellCommandFactory(ShellMode.POWERSHELL, parentShellName);
        }
        return new ShellCommandFactory(ShellMode.POWERSHELL, defaultWindowsPowerShellCmd());
    }

    /**
     * 不可判定父 shell 时的 PowerShell 可执行名：优先系统自带的 {@code powershell.exe}（按文件
     * 存在判定，不 fork 进程）；若被移除（部分精简镜像/Windows 未来版本）则用 {@code pwsh}。
     */
    static String defaultWindowsPowerShellCmd() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isEmpty()) {
            systemRoot = "C:\\Windows";
        }
        Path legacy = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return Files.isRegularFile(legacy) ? "powershell" : "pwsh";
    }

    /**
     * 探测 Windows 下真实的父 shell：向上遍历祖先进程（最多 {@value #MAX_PARENT_WALK} 层），
     * 按进程映像路径返回 {@code "pwsh"} / {@code "powershell"} / {@code "cmd"}；全部不可判定返回
     * {@code null}（结果带缓存，进程内只探测一次）。
     *
     * <p><b>为何不用 {@code ProcessHandle}</b>：它自 JDK 9 才有，而本模块需要在 Java 8 上可编译、
     * 可运行。改为外挂一次 PowerShell 查询祖先链，Java 8 与新版 JDK 走同一条码路，不存在
     * 「两套实现行为漂移」的隐患。</p>
     *
     * <p>进程信息受平台权限影响可能取不到（映像路径为空），此时退用进程名；整条链取不到则
     * 返回 {@code null}，由 {@link #windowsShellOf(String)} 回退默认 PowerShell——与改造前
     * {@code catch} 后回退的语义一致。</p>
     *
     * @see #ancestorCommands()
     */
    static String detectWindowsParentShellName() {
        String cached = cachedWindowsParentShell;
        if (cached == null) {
            synchronized (ShellCommandFactory.class) {
                cached = cachedWindowsParentShell;
                if (cached == null) {
                    cached = probeWindowsParentShellName();
                    cachedWindowsParentShell = (cached == null) ? PARENT_PROBE_FAILED : cached;
                }
            }
        }
        return PARENT_PROBE_FAILED.equals(cached) ? null : cached;
    }

    private static String probeWindowsParentShellName() {
        if (!EnvironmentResolver.isWindows()) {
            // 类 Unix 下 detect() 走 probeUnixShell()，从不需要祖先链；这里守住「别在 Linux 上 fork powershell」
            return null;
        }
        for (String cmd : ancestorCommands()) {
            String name = shellNameOfCommand(cmd);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /**
     * 由祖先进程的映像路径/进程名判定 shell 名；不可判定返回 {@code null}。
     *
     * <p>抽成纯函数是为了可测：真实祖先链随启动方式漂移（surefire fork ← mvn ← 某个 shell），
     * 无法直接断言。入参需已转小写。</p>
     */
    static String shellNameOfCommand(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return null;
        }
        // pwsh 必须先判：传统 5.1 的路径含 WindowsPowerShell，两个关键字不会同时命中，
        // 但用户自定义安装路径（如 D:\PowerShell\7\pwsh.exe）可能两者都含
        if (cmd.contains("pwsh")) {
            return "pwsh";
        }
        if (cmd.contains("powershell")) {
            return "powershell";
        }
        if (cmd.contains("cmd.exe") || cmd.endsWith("\\cmd") || cmd.equals("cmd")) {
            return "cmd";
        }
        return null;
    }

    /**
     * 祖先链探测脚本：一次取回全部进程的 {@code (pid, ppid, 映像路径)}，在内存里从探测进程
     * 自身逐级向上走，输出映像路径（取不到则输出进程名）。
     *
     * <p><b>为何用 {@code Get-CimInstance} 而不是 {@code wmic.exe}</b>：WMIC 已被微软从 Windows 11
     * 移除、且不再作为可选功能提供（WMI 服务本身不受影响，{@code Get-CimInstance} 继续可用）。
     * {@code tasklist} 不输出父进程 ID，PowerShell 5.1 的 {@code Get-Process} 也没有 {@code .Parent}
     * 属性（7+ 才有），因此本机查父进程只剩 CIM/WMI 一条路；老环境（PowerShell 2.0）无
     * {@code Get-CimInstance}，降级到 {@code Get-WmiObject}。</p>
     *
     * <p><b>为何一次拉全量而不是逐层 {@code -Filter ProcessId=}</b>：后者要发 8 次 WMI 查询，
     * 实测总耗时 ≈ 2.9s，而单次全量查询 + 内存遍历 ≈ 1.2s。探测在 {@link #detect()} 的同步
     * 起动路径上，这一倍多的开销直接反映为启动延迟。</p>
     *
     * <p><b>为何全程不用双引号</b>：这段脚本作为单个 argv 元素传给 PowerShell，JDK 会为含空格的
     * 参数补引号并把内层 {@code "} 转义成 {@code \"}。全用单引号可完全绕开这层转义差异。</p>
     *
     * <p>编码用无 BOM 的 UTF-8：{@code [Text.Encoding]::UTF8} 带 preamble，会让首行多出
     * {@code \uFEFF} 而破坏行标记匹配。</p>
     */
    private static final String ANCESTOR_PROBE_SCRIPT =
            "$ErrorActionPreference='SilentlyContinue';"
                    + " try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { };"
                    + " $useCim = $null -ne (Get-Command Get-CimInstance -ErrorAction SilentlyContinue);"
                    + " if ($useCim) { $all = Get-CimInstance -ClassName Win32_Process"
                    + " -Property ProcessId,ParentProcessId,ExecutablePath,Name }"
                    + " else { $all = Get-WmiObject -Class Win32_Process"
                    + " -Property ProcessId,ParentProcessId,ExecutablePath,Name };"
                    + " $map = @{};"
                    + " foreach ($p in $all) { $map[[int]$p.ProcessId] = $p };"
                    + " $id = [int]$PID;"
                    + " for ($i = 0; $i -lt " + (PARENT_WALK_SKIP + MAX_PARENT_WALK) + "; $i++) {"
                    + "   $p = $map[$id];"
                    + "   if ($null -eq $p) { break };"
                    + "   if ($i -ge " + PARENT_WALK_SKIP + ") {"
                    + "     if ($p.ExecutablePath) { '" + PARENT_PROBE_MARK + "' + $p.ExecutablePath }"
                    + "     else { '" + PARENT_PROBE_MARK + "' + $p.Name }"
                    + "   };"
                    + "   $id = [int]$p.ParentProcessId;"
                    + "   if ($id -eq 0) { break }"
                    + " }";

    /**
     * 取「JVM 的父进程」起、最多 {@value #MAX_PARENT_WALK} 层祖先的映像路径（已转小写，由近及远）。
     * 任何失败（无 WMI、禁止创建子进程、超时）都返回空列表，绝不抛出。
     *
     * <p>骨架与 {@code EnvironmentResolver.resolveWindowsPath()} 一致：独立守护线程消费输出，
     * 主线程按超时等待——同步读流会让超时形同虚设。</p>
     */
    private static List<String> ancestorCommands() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(defaultWindowsPowerShellCmd(),
                    "-NoProfile", "-NonInteractive", "-Command", ANCESTOR_PROBE_SCRIPT);
            pb.redirectErrorStream(true);
            process = pb.start();

            final Process proc = process;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        synchronized (buf) {
                            buf.write(buffer, 0, n);
                            if (buf.size() > 64 * 1024) {
                                proc.destroyForcibly();
                                break;
                            }
                        }
                    }
                } catch (Throwable ignore) {
                    // 进程被强杀或流关闭，读取线程静默退出
                }
            }, "shell-parent-probe");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(PARENT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.debug("Detect parent shell timed out");
                return Collections.emptyList();
            }
            reader.join(200);

            String output;
            synchronized (buf) {
                output = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            return parseAncestorCommands(output);
        } catch (Throwable e) {
            if (process != null) {
                process.destroyForcibly();
            }
            LOG.debug("Detect parent shell failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从探测输出里挑出带行标记的祖先映像路径（转小写、去标记）。无标记的行一律丢弃：
     * 那是合并进来的 PowerShell 报错文本，其中可能含 {@code powershell} 字样。
     */
    static List<String> parseAncestorCommands(String output) {
        List<String> commands = new ArrayList<>();
        if (output == null) {
            return commands;
        }
        for (String line : output.split("\\r?\\n")) {
            String s = line.trim().toLowerCase();
            if (s.startsWith("\uFEFF")) {
                s = s.substring(1).trim();
            }
            if (s.startsWith(PARENT_PROBE_MARK)) {
                String cmd = s.substring(PARENT_PROBE_MARK.length()).trim();
                if (!cmd.isEmpty()) {
                    commands.add(cmd);
                }
            }
        }
        return commands;
    }

    /**
     * 按祖先进程链推定的 shell 类型（不可判定时返回 {@link ShellMode#POWERSHELL}）。
     *
     * @see #detectWindowsParentShellName()
     */
    static ShellMode detectWindowsParentShell() {
        return "cmd".equals(detectWindowsParentShellName()) ? ShellMode.CMD : ShellMode.POWERSHELL;
    }

    public ShellMode getShellMode() {
        return shellMode;
    }

    public String getShellCmd() {
        return shellCmd;
    }

    /**
     * 是否为 Windows 系 shell（CMD / PowerShell）。
     */
    public boolean isWindowsShell() {
        return shellMode == ShellMode.CMD || shellMode == ShellMode.POWERSHELL;
    }

    /**
     * 是否为跳平台的 PowerShell 7+（{@code pwsh}）。
     *
     * <p>与 Windows PowerShell 5.1 的差异会直接影响引导词的正确性：pwsh 支持 {@code &&} /
     * {@code ||}（自 7.0），且文件读写默认已是无 BOM 的 UTF-8——把 5.1 的限制当作 pwsh 的限制
     * 告知模型，等于无故降级了可用能力。</p>
     */
    public boolean isPowerShellCore() {
        return shellMode == ShellMode.POWERSHELL && shellCmd.toLowerCase().contains("pwsh");
    }

    /**
     * 将用户命令组装为直接执行的进程参数列表（不落临时脚本）。
     */
    public List<String> build(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        if (shellMode == ShellMode.POWERSHELL) {
            return Arrays.asList(shellCmd, "-Command", command);
        }
        if (shellMode == ShellMode.CMD) {
            return Arrays.asList(shellCmd, "/c", command);
        }
        return Arrays.asList(shellCmd, "-lc", command);
    }

    /**
     * 为 Windows 系 shell 构建「可靠启动方案」（解决命令文本编码与多行/超长命令问题）。
     *
     * <p>Windows 下直接 {@code cmd /c <command>} 或 {@code powershell -Command <command>} 的固有问题：
     * <ul>
     *   <li><b>命令文本被改写</b>：命令串经 Unicode→代码页转换，ANSI 不可表示的字符会丢失。</li>
     *   <li><b>多行命令不可用</b>：命令行里的换行会被 cmd 截断，只执行首行。</li>
     *   <li><b>PowerShell {@code -Command}</b> 会把整串当 PowerShell 语法求值（$ 变量、反引号转义等）。</li>
     * </ul>
     *
     * <p>对策：
     * <ul>
     *   <li><b>CMD</b>：默认仍走 {@code cmd /d /c <command>} 直连（不落文件），仅在直连确实不可靠时
     *       （命令含换行、含 ANSI 无法表示的字符、或超出命令行长度上限）才写临时 {@code .bat}。
     *       <b>之所以不无条件落 .bat</b>：批处理文件与命令行的 {@code %} 语义不同——{@code %1}/{@code %*}
     *       会被当脚本参数替换、{@code for %i} 必须写成 {@code for %%i}、{@code %%} 会被折叠为一个 {@code %}。
     *       无条件落盘会静默改变所有单行命令的行为；限定在「直连本就不可用」的场景，则只增能力、无回归。
     *       （引号不在触发条件内：Java 的 argv 拼装会把整条命令包一层引号，而 {@code cmd /c} 的
     *       「剥离首尾引号」规则正好与之互逆，含引号的单行命令直连即可正确执行。例外是显式设置
     *       {@code -Djdk.lang.Process.allowAmbiguousCommands=false} 时 JDK 会把内层引号转义成 {@code \"}，
     *       cmd 不认该转义——该开关为非默认的 opt-in 行为，此处不为它牺牲 {@code %} 语义。）</li>
     *   <li><b>落 .bat 时的编码</b>：默认按系统 ANSI 代码页写入、不动 chcp。不用 chcp 的原因：它改的是
     *       共享控制台的代码页（会污染宿主进程自身的输出），且 cmd 按字节偏移逐行读批处理文件，
     *       在文件中途切代码页是已知的解析错位来源。仅当命令含 ANSI 无法表示的字符（如 emoji、
     *       GBK 缺字）时，才降级为 UTF-8 + 首行 {@code chcp 65001}。</li>
     *   <li><b>PowerShell</b>：优先 {@code -EncodedCommand}（Base64 of UTF-16LE）：不经代码页转换、
     *       不落文件、不受 ExecutionPolicy 限制，且退出码语义与原 {@code -Command} 一致。
     *       （{@code -File} 执行脚本需策略放行，而命令行上的 {@code -ExecutionPolicy Bypass}
     *       会被 GPO 策略覆盖，域环境里可能直接无法加载脚本。）仅当命令过长超出命令行
     *       限制时，才回退到 UTF-8 BOM 的 {@code .ps1} 文件。</li>
     * </ul>
     *
     * <p>输出侧的编码不依赖 chcp：子进程输出被重定向到管道时，多数工具按 ANSI 代码页写，
     * 不受控制台代码页影响；统一由 {@link OutputDecoder}（UTF-8 严格校验 + ANSI 代码页兜底）
     * 与 {@code PYTHONIOENCODING=utf-8} 处理。</p>
     *
     * <p>非 Windows 返回 {@code null}（保持 {@code shell -lc} 直连）。调用方必须在执行完成后
     * 调用 {@link PreparedCommand#cleanup()}（无临时文件时为空操作）。</p>
     *
     * @param command     用户命令
     * @param interactive 进程是否可能需要读取 stdin（会话式执行）。为 {@code true} 时不加
     *                    PowerShell 的 {@code -NonInteractive}，否则 {@code bash_wait(chars)} 想应答的
     *                    确认提示会直接失败而不是等待输入。
     */
    public PreparedCommand prepare(String command, boolean interactive) throws IOException {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        if (shellMode == ShellMode.CMD) {
            return prepareCmd(command);
        }
        if (shellMode == ShellMode.POWERSHELL) {
            return preparePowerShell(command, interactive);
        }
        return null; // Unix 直连，不落临时脚本
    }

    /**
     * 等价于 {@code prepare(command, false)}：用于同步、不读 stdin 的执行路径。
     *
     * @see #prepare(String, boolean)
     */
    public PreparedCommand prepare(String command) throws IOException {
        return prepare(command, false);
    }

    private PreparedCommand prepareCmd(String command) throws IOException {
        Charset ansi = OutputDecoder.ansiCharset(null);
        boolean ansiEncodable = ansi == null || ansi.newEncoder().canEncode(command);
        boolean multiLine = command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0;

        if (ansiEncodable && !multiLine && command.length() <= MAX_CMD_LINE_CHARS) {
            // 直连：保持命令行的 % 语义（%1/%*/for %i 等），/d 顺带屏蔽注册表 AutoRun 对输出的污染
            return new PreparedCommand(Arrays.asList(shellCmd, "/d", "/c", command), null);
        }

        // 落 .bat：命令按批处理语义执行（多行脚本本就期望批处理规则）
        boolean useAnsi = ansi != null && ansiEncodable;
        String header = useAnsi ? "@echo off\r\n" : "@echo off\r\n@chcp 65001 > nul\r\n";
        Charset writeCharset = useAnsi ? ansi : StandardCharsets.UTF_8;

        Path script = ProcessExecutor.createTempScript(".bat", false);
        try {
            Files.write(script, (header + command + "\r\n").getBytes(writeCharset));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(script);
            throw e;
        }
        return new PreparedCommand(
                Arrays.asList(shellCmd, "/d", "/c", script.toAbsolutePath().toString()),
                script);
    }

    private PreparedCommand preparePowerShell(String command, boolean interactive) throws IOException {
        String full = POWERSHELL_PREAMBLE + command;

        List<String> flags = interactive
                ? Arrays.asList("-NoProfile")
                : Arrays.asList("-NoProfile", "-NonInteractive");

        String encoded = Base64.getEncoder().encodeToString(full.getBytes(StandardCharsets.UTF_16LE));
        if (encoded.length() <= MAX_ENCODED_COMMAND_CHARS) {
            List<String> argv = new ArrayList<>();
            argv.add(shellCmd);
            argv.addAll(flags);
            argv.add("-EncodedCommand");
            argv.add(encoded);
            return new PreparedCommand(argv, null);
        }

        // 超长命令后备：落 .ps1 文件（需 ExecutionPolicy 放行，可能被 GPO 限制，仅作最后手段）
        Path script = ProcessExecutor.createTempScript(".ps1", false);
        try {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; // Windows PowerShell 5.1 依赖 BOM 识别 UTF-8
            byte[] body = full.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, bytes, 0, bom.length);
            System.arraycopy(body, 0, bytes, bom.length, body.length);
            Files.write(script, bytes);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(script);
            throw e;
        }
        List<String> argv = new ArrayList<>();
        argv.add(shellCmd);
        argv.addAll(flags);
        argv.add("-ExecutionPolicy");
        argv.add("Bypass");
        argv.add("-File");
        argv.add(script.toAbsolutePath().toString());
        return new PreparedCommand(argv, script);
    }

    /**
     * 一次已就绪的进程启动方案：argv + 待清理的临时脚本（非 Windows 场景 tempScript 为 null）。
     */
    public static final class PreparedCommand {
        private final List<String> argv;
        private final Path tempScript;

        PreparedCommand(List<String> argv, Path tempScript) {
            this.argv = argv;
            this.tempScript = tempScript;
        }

        public List<String> argv() {
            return argv;
        }

        public Path tempScript() {
            return tempScript;
        }

        /**
         * 删除临时脚本（幂等）。无临时脚本时为空操作。
         *
         * <p>删除失败则临时文件会残留在系统临时目录（未注册 {@code deleteOnExit}：其钩子集合只增不减，
         * 长驻进程里每条命令都注册会造成慢性内存增长）。文件名前缀为 {@code solon-ai-script-}。</p>
         */
        public void cleanup() {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (IOException ignored) {
                    // 残留由系统临时目录清理策略处理
                }
            }
        }
    }

    static String probeUnixShell() {
        String cached = cachedUnixShell;
        if (cached != null) {
            return cached;
        }
        synchronized (ShellCommandFactory.class) {
            if (cachedUnixShell != null) {
                return cachedUnixShell;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
                p.destroyForcibly();
                cachedUnixShell = ok ? "bash" : "/bin/sh";
            } catch (Throwable e) {
                cachedUnixShell = "/bin/sh";
            }
            return cachedUnixShell;
        }
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
