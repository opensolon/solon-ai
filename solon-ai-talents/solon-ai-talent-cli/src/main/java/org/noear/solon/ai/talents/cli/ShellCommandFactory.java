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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

    /**
     * {@code -EncodedCommand} 的 Base64 长度上限。Windows 命令行总长约 32767 字符，
     * 留足前缀与余量；超限则回退到临时 {@code .ps1} 脚本。
     */
    private static final int MAX_ENCODED_COMMAND_CHARS = 24_000;

    /**
     * {@code cmd /c} 命令行的长度上限（约 8191），留出前缀与余量；超限则落临时 {@code .bat}。
     */
    private static final int MAX_CMD_LINE_CHARS = 7_500;

    private static volatile String cachedUnixShell;

    private final ShellMode shellMode;
    private final String shellCmd;

    public ShellCommandFactory(ShellMode shellMode, String shellCmd) {
        this.shellMode = Objects.requireNonNull(shellMode, "shellMode");
        this.shellCmd = requireNonEmpty(shellCmd, "shellCmd");
    }

    /**
     * 按当前 OS / COMSPEC 探测默认 shell。
     */
    public static ShellCommandFactory detect() {
        if (EnvironmentResolver.isWindows()) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && comspec.toLowerCase().contains("powershell")) {
                return new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
            }
            return new ShellCommandFactory(ShellMode.CMD, "cmd");
        }
        return new ShellCommandFactory(ShellMode.UNIX_SHELL, probeUnixShell());
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
     *                    PowerShell 的 {@code -NonInteractive}，否则 {@code bash_stdin} 想应答的
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
        // 前置：把输出编码设为 UTF-8，使 native 子命令的输出能被 PowerShell 正确读取。
        // 必须用无 BOM 的 UTF8Encoding：[System.Text.Encoding]::UTF8 带 preamble，赋给
        // [Console]::OutputEncoding 后输出开头会多出 \uFEFF；且无控制台句柄时该赋值可能抛
        // IOException，包 try/catch 避免首行报错混进合并后的输出流。
        String full = "$OutputEncoding = New-Object System.Text.UTF8Encoding $false\r\n"
                + "try { [Console]::OutputEncoding = $OutputEncoding } catch { }\r\n"
                + command;

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
