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

import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 外部命令行执行器
 *
 * <p>提供通用的代码持久化、子进程启动、标准输出捕获及执行超时控制。
 * 具备输出截断保护机制，防止大数据量输出导致内存溢出。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ProcessExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessExecutor.class);

    private static final int DEFAULT_TIMEOUT_MS = 120_000; //120s
    /** 进程结束后等待输出读取线程排空管道的时间上限 */
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 5_000;

    // 面向 LLM 的默认输出字符上限。
    public static final int DEFAULT_LLM_OUTPUT_CHARS = 64_000;
    // 二进制探测的采样字节数
    private static final int BINARY_PROBE_SAMPLE = 8_192;
    // 二进制摘要中预览的字节数
    private static final int BINARY_PREVIEW_BYTES = 32;

    private int maxOutputSize = 1024 * 1024; // 默认 1MB
    private Charset scriptCharset = StandardCharsets.UTF_8;
    private Charset outputCharset = StandardCharsets.UTF_8;

    public int getMaxOutputSize() {
        return maxOutputSize;
    }

    public Charset getScriptCharset() {
        return scriptCharset;
    }

    public Charset getOutputCharset() {
        return outputCharset;
    }

    /**
     * 配置最大输出大小（字节）
     */
    public void setMaxOutputSize(int maxOutputSize) {
        this.maxOutputSize = maxOutputSize;
    }

    public void setScriptCharset(Charset scriptCharset) {
        this.scriptCharset = scriptCharset;
    }

    public void setOutputCharset(Charset outputCharset) {
        this.outputCharset = outputCharset;
    }

    /**
     * 探测系统命令是否可用
     *
     * <p><b>为何输出必须交给独立守护线程</b>：本方法处在对话的同步路径上（{@code getInstruction} /
     * {@code bash} 首次调用都会触发运行时探测）。若在当前线程同步读到 EOF，遇到「启动后不退出也不关
     * 输出流」的可执行文件（Windows 应用商店 python 存根、等待输入的 wrapper 脚本、被安全软件挂起的
     * 进程）就会永久阻塞，下面的 {@code waitFor(2s)} 形同虚设——表现为整个会话卡死。</p>
     */
    public boolean isCommandAvailable(String cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            // 注入实时 PATH（Windows），使新安装的运行时能被探测到，无需重启 JVM
            EnvironmentResolver.applyTo(pb, null);
            process = pb.start();
            // 探测不需要输入：立即给出 EOF，避免读 stdin 的 wrapper 脚本挂在这里
            closeQuietly(process.getOutputStream());

            final Process proc = process;
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[1024];
                    while (in.read(buf) != -1) ;
                } catch (Throwable ignore) {
                    // 进程被强杀或流关闭，读取线程静默退出
                }
            }, "solon-ai-probe-reader");
            reader.setDaemon(true);
            reader.start();

            if (process.waitFor(2, TimeUnit.SECONDS)) {
                return process.exitValue() == 0;
            } else {
                return false; // 超时视作不可用
            }
        } catch (Throwable e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly(); // 关键：确保探测进程一定关闭
            }
        }
    }

    /**
     * 关闭流并忽略异常（进程已退出时 close 会抛 IOException）。
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 进程已退出/管道已断开，无需处理
        }
    }


    /**
     * 探测可用的 Python 命令。
     *
     * <p><b>Windows 上必须先试 {@code python}</b>：python.org 安装器只会放 {@code python.exe}，
     * 而 {@code python3} 在未安装 Store 版本时会解析到
     * {@code %LOCALAPPDATA%\Microsoft\WindowsApps\python3.exe} 这个应用商店存根（执行它会弹商店
     * 而不是跑 Python）。先试 {@code python3} 就会反复撞上存根，白白多花一次探测超时。
     * 类 Unix 上相反：{@code python} 可能指向已停维的 Python 2，应优先 {@code python3}。</p>
     */
    public String probePythonCommand() {
        String[] candidates = EnvironmentResolver.isWindows()
                ? new String[]{"python", "python3"}
                : new String[]{"python3", "python"};
        for (String candidate : candidates) {
            if (isCommandAvailable(candidate)) {
                return candidate;
            }
        }
        return null; // 没找到
    }

    public String probeNodeCommand() {
        if (isCommandAvailable("node")) {
            return "node";
        }
        if (isCommandAvailable("nodejs")) {
            return "nodejs";
        }
        return null; // 或者返回 ""，表示没找到
    }

    /**
     * 执行代码脚本（持久化为系统临时文件后执行）。
     *
     * <p>临时脚本写入 {@code java.io.tmpdir}，避免污染工作区；
     * 执行结束后立即删除，并注册 {@code deleteOnExit} 作为兜底。</p>
     */
    public String executeCode(Path rootPath, String code, String cmd, String ext, Map<String, String> envs, Integer timeoutMs, Integer maxOutputChars, Consumer<String> onOutput) {
        if (Assert.isEmpty(cmd)) {
            return "执行失败: 未找到可用的运行命令（Command not found）";
        }

        Path tempScript = null;
        try {
            // 1. 持久化脚本到系统临时目录（Windows .bat：仅当脚本按 UTF-8 写入时才需 chcp 65001 切页）
            String finalCode = code;
            if (".bat".equals(ext) && StandardCharsets.UTF_8.equals(scriptCharset)) {
                finalCode = "@chcp 65001 > nul\r\n" + code;
            }
            tempScript = createTempScript(ext);
            Files.write(tempScript, finalCode.getBytes(scriptCharset));

            // 2. 构建完整命令（处理带空格的命令字符串）
            List<String> fullCmd = CmdUtil.parseArguments(cmd);
            fullCmd.add(tempScript.toAbsolutePath().toString());

            return executeCmd(rootPath, fullCmd, envs, timeoutMs, maxOutputChars, onOutput);
        } catch (Throwable e) {
            LOG.error("Code execution failed", e);
            return "代码执行失败: " + e.getMessage();
        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 在系统临时目录创建脚本文件：前缀 solon-ai-script-，Unix 尽量收紧为 600。
     */
    static Path createTempScript(String ext) throws IOException {
        return createTempScript(ext, true);
    }

    /**
     * 在系统临时目录创建脚本文件。
     *
     * @param registerDeleteOnExit 是否注册 {@code deleteOnExit} 兜底。调用方能保证主动删除时应传
     *                             {@code false}：{@code DeleteOnExitHook} 的集合只增不减，长驻进程里
     *                             每次命令都注册会造成慢性内存增长。
     */
    static Path createTempScript(String ext, boolean registerDeleteOnExit) throws IOException {
        Path tempScript = Files.createTempFile("solon-ai-script-", ext);
        if (registerDeleteOnExit) {
            // JVM 异常退出时的兜底清理；正常路径仍由 finally 主动删除
            tempScript.toFile().deleteOnExit();
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(tempScript, perms);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统（如 Windows）静默降级
        }
        return tempScript;
    }

    /**
     * 执行完整命令，支持实时输出回调及面向 LLM 的输出字符上限。
     *
     * <p>{@code maxOutputChars} 仅约束最终返回给模型的文本（头尾保留 + 二进制降级），
     * 不影响 {@code onOutput} 的实时流式回调。</p>
     */
    public String executeCmd(Path rootPath, List<String> fullCmd, Map<String, String> envs, Integer timeoutMs, Integer maxOutputChars, Consumer<String> onOutput) {
        if (timeoutMs == null || timeoutMs < 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.directory(rootPath.toFile());
            pb.redirectErrorStream(true);

            // 注入实时系统 PATH（Windows：修复 JVM 环境快照不刷新导致新装命令不可见）；
            // 显式 envs（如 PYTHON/NODE）优先级更高
            EnvironmentResolver.applyTo(pb, envs);

            Process process = pb.start();
            // 非交互执行：立刻关闭子进程 stdin，让读取输入的命令拿到 EOF 而不是一直等到超时。
            // 不关的话（ProcessBuilder 默认给 stdin 挂一个没人写的管道），`git commit`（拉起编辑器）、
            // `npm login`、Unix 的 `read`、CMD 的 `pause` 这类命令会稳定空转到 timeoutMs 才返回——
            // 三个平台同病。需要交互输入的场景走 bash_start / bash_wait(chars)（那条路径保留 stdin）
            closeQuietly(process.getOutputStream());

            // 1. 异步读取输出（在字节层收集，便于可靠的二进制探测）
            final OutputDecoder streamDecoder = onOutput == null ? null : new OutputDecoder(outputCharset);
            // PowerShell 的 stderr 被重定向时会强制写出 CLIXML 块：必须在解码前剥离，否则
            // 「CLIXML(ANSI 代码页) + stdout(UTF-8)」两种编码混在一条流里，字符集只能锁一种，另一半必乱码
            final CliXmlFilter cliXmlFilter = CliXmlFilter.isNeeded() ? new CliXmlFilter() : null;
            // 读取线程写、主线程读：超时路径下 awaitOutput 可能在读取线程仍在收尾时就返回，
            // 因此必须用线程安全的 StringBuffer，避免此时 toString() 撞上并发修改
            final StringBuffer cliXmlMessages = new StringBuffer();
            // 输出是否撞上物理字节上限（maxOutputSize）：撞上后进程会被终止，必须显式告知模型
            // 「这不是命令自然结束」，否则它会把被截断的输出当成完整结果继续推理
            final boolean[] sizeCapped = {false};
            CompletableFuture<byte[]> outputFuture = RunUtil.async(() -> {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        byte[] chunk = buffer;
                        int len = n;
                        if (cliXmlFilter != null) {
                            chunk = cliXmlFilter.accept(buffer, n);
                            len = chunk.length;
                            if (len == 0) {
                                continue; // 本段全部落在 CLIXML 块内（或为待定的标记前缀）
                            }
                        }

                        if (onOutput != null) {
                            // 实时回调：增量解码，避免多字节字符被读取分块切断
                            String piece = streamDecoder.decode(chunk, len);
                            if (!piece.isEmpty()) {
                                onOutput.accept(piece);
                            }
                        }

                        if (buf.size() + len <= maxOutputSize) {
                            buf.write(chunk, 0, len);
                        } else {
                            int remaining = maxOutputSize - buf.size();
                            if (remaining > 0) {
                                buf.write(chunk, 0, remaining);
                            }
                            // 物理上限兜底：防止超大输出撑爆内存。必须整树终止——Windows 无进程组信号，
                            // 只杀 shell 会让孙进程（node/mvn/java 等）孤儿化继续跑
                            sizeCapped[0] = true;
                            TerminalSessionManager.destroyProcessTree(process);
                            break;
                        }
                    }
                } catch (IOException e) {
                    LOG.debug("Stream reading interrupted: {}", e.getMessage());
                }
                if (cliXmlFilter != null) {
                    byte[] rest = cliXmlFilter.flush();
                    if (rest.length > 0 && buf.size() + rest.length <= maxOutputSize) {
                        buf.write(rest, 0, rest.length);
                        if (onOutput != null) {
                            String piece = streamDecoder.decode(rest, rest.length);
                            if (!piece.isEmpty()) {
                                onOutput.accept(piece);
                            }
                        }
                    }
                    cliXmlMessages.append(cliXmlFilter.drainMessages());
                }
                if (streamDecoder != null) {
                    String tail = streamDecoder.flush();
                    if (!tail.isEmpty()) {
                        onOutput.accept(tail);
                    }
                }
                if (onOutput != null && cliXmlMessages.length() > 0) {
                    onOutput.accept(cliXmlMessages.toString());
                }
                return buf.toByteArray();
            });

            // 2. 超时控制：必须整树终止。Windows 没有进程组信号，只 destroyForcibly 根 shell 的话，
            // `cmd /d /c mvn ...` / `powershell ... npm run dev` 的孙进程会孤儿化并继续占用端口
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                TerminalSessionManager.destroyProcessTree(process);
                // 超时前已产生的输出是最有价值的诊断线索（卡在哪一步、最后一条日志是什么），
                // 不能连同超时一起丢弃。进程树已终止，读取线程会随管道关闭而收尾，故此处等待极短
                String partial = renderOutput(awaitOutput(outputFuture, OUTPUT_DRAIN_TIMEOUT_MS),
                        streamDecoder, cliXmlMessages, maxOutputChars);
                String head = "执行超时：运行时间超过 " + timeoutMs + " 毫秒（已终止进程树）。";
                return partial.isEmpty() ? head : (head + "\n--- 超时前已产生的输出 ---\n" + partial);
            }

            // 3. 获取原始字节输出（读取线程可能仍在收尾：给足排空时间，而不是 1 秒硬超时后抛异常
            // ——那会让模型收到一句无信息量的「系统失败: null」）
            byte[] raw = awaitOutput(outputFuture, OUTPUT_DRAIN_TIMEOUT_MS);

            // 二进制输出降级：在字节层探测，避免乱码塞满上下文（如 cat 某个 jar/class/图片）
            if (isLikelyBinary(raw)) {
                return summarizeBinaryOutput(raw);
            }

            String result = renderOutput(raw, streamDecoder, cliXmlMessages, maxOutputChars);

            if (sizeCapped[0]) {
                result = result + "\n... [输出超过 " + maxOutputSize
                        + " 字节的物理上限，命令已被终止。请缩小输出范围（如加过滤/分页），或重定向到文件后再分段查看]";
            }

            // 退出码：非零时必须显式给出。否则命令失败（编译报错、测试失败、脚本 exit 1）时模型只能
            // 从输出文本里猜成败，而 PowerShell 的错误对象经 CLIXML 剥离后已丢失与正文的交错顺序
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                result = (result.isEmpty() ? "" : result + "\n") + "[exit_code=" + exitCode + "]";
            }

            if (result.isEmpty()) {
                return "执行成功";
            }
            return result;

        } catch (Throwable e) {
            LOG.error("Process execution failed", e);
            return "系统失败: " + e;
        }
    }

    /**
     * 等待输出读取线程交出已收集的字节；超时或失败时返回已知的空结果（绝不因此让整条命令报错）。
     */
    private static byte[] awaitOutput(CompletableFuture<byte[]> outputFuture, long timeoutMs) {
        try {
            return outputFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            LOG.debug("Collect process output failed: {}", e.toString());
            return new byte[0];
        }
    }

    /**
     * 把原始字节渲染为面向 LLM 的文本：解码 + 追加 CLIXML 文本记录 + 头尾保留截断。
     *
     * @param streamDecoder   流式回调用过的解码器（可为 null）：已锁定字符集时必须复用，否则同一次执行里
     *                        「实时输出」与「最终返回值」会按不同字符集解码，给出两份不一致的文本
     * @param cliXmlMessages  CLIXML 里还原出的 error/warning 文本（与主流不同编码，只能单独解码，
     *                        因此无法保持原始交错顺序，统一补在末尾）
     */
    private String renderOutput(byte[] raw, OutputDecoder streamDecoder,
                                StringBuffer cliXmlMessages, Integer maxOutputChars) {
        Charset locked = streamDecoder == null ? null : streamDecoder.selectedCharset();
        String result = (raw == null || raw.length == 0)
                ? ""
                : (locked == null ? decodeSmartly(raw, outputCharset) : new String(raw, locked)).trim();

        if (cliXmlMessages.length() > 0) {
            String extra = cliXmlMessages.toString().trim();
            if (!extra.isEmpty()) {
                result = result.isEmpty() ? extra : (result + "\n" + extra);
            }
        }

        return truncateForLlm(result, normalizeMaxOutputChars(maxOutputChars));
    }

    private static int normalizeMaxOutputChars(Integer maxOutputChars) {
        if (maxOutputChars == null || maxOutputChars <= 0) {
            return DEFAULT_LLM_OUTPUT_CHARS;
        }
        return maxOutputChars;
    }

    /**
     * 智能解码子进程输出（一次性完整字节）：委托 {@link OutputDecoder#decodeAll}。
     *
     * <p>期望字符集（{@code outputCharset}，默认 UTF-8）优先，严格校验不通过时按平台遗留代码页
     * （Windows 中文系统为 GBK）重解，避免第三方工具按 ANSI 代码页输出导致乱码。</p>
     */
    static String decodeSmartly(byte[] raw, Charset primary) {
        return OutputDecoder.decodeAll(raw, primary);
    }

    /**
     * 探测输出是否疑似二进制流（字节层判定）。
     *
     * <p>在原始字节上采样，统计 NUL 及非空白控制字符占比；超过阈值则判定为二进制。
     * 之所以在字节层而非解码后的字符串层判定，是因为二进制字节经 UTF-8 解码后会
     * 被替换成可打印的 U+FFFD，导致字符串层探测失效（如 jar/class 漏判）。</p>
     *
     * <p>优化策略：<ul>
     *   <li>白名单扩容：除 \n \r \t ESC 外，增加 \b（退格）、\f（换页），减少常见终端输出误判</li>
     *   <li>多区域采样：头、中、尾各取 1/3，避免头部进度条噪音主导判定</li>
     *   <li>长段连续文本启发式：若采样中发现超过 120 字节的连续可打印文本段，大幅放宽容忍度</li>
     *   <li>文本占优启发式：若可打印 ASCII 占比 &gt; 70%，提高控制字符阈值至 45%</li>
     * </ul></p>
     */
    static boolean isLikelyBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }

        // 第1关：NUL 扫描（强特征快速正判，最大扫描 64KB 避免大文件性能开销）
        int nulScanLen = Math.min(bytes.length, 65536);
        for (int i = 0; i < nulScanLen; i++) {
            if ((bytes[i] & 0xFF) == 0x00) {
                return true;
            }
        }

        // 第2关：多区域采样（头/中/尾各取 1/3，去重合并索引）
        int totalLen = bytes.length;
        int sample = Math.min(totalLen, BINARY_PROBE_SAMPLE);
        int regionSize = Math.max(1, sample / 3);

        boolean[] visited = new boolean[sample];
        int suspicious = 0;       // 可疑控制字符计数
        int printable = 0;        // 可打印 ASCII 计数
        int maxPrintableRun = 0;  // 最长连续可打印段
        int currentRun = 0;       // 当前连续可打印计数
        int checked = 0;          // 实际检测字节数

        int[] regionStarts = {
                0,
                Math.max(0, totalLen / 2 - regionSize / 2),
                Math.max(0, totalLen - regionSize)
        };

        for (int ri = 0; ri < 3 && checked < sample; ri++) {
            int start = regionStarts[ri];
            int end = Math.min(start + regionSize, totalLen);
            if (start >= totalLen) continue;

            for (int i = start; i < end && checked < sample; i++) {
                int vi = i - regionStarts[0];
                if (vi >= 0 && vi < sample && visited[vi]) continue;
                if (vi >= 0 && vi < sample) visited[vi] = true;
                checked++;

                int b = bytes[i] & 0xFF;

                // 可打印 ASCII (0x20-0x7E) → 文本的强证据
                if (b >= 0x20 && b <= 0x7E) {
                    printable++;
                    currentRun++;
                    maxPrintableRun = Math.max(maxPrintableRun, currentRun);
                    continue;
                }
                currentRun = 0;

                // 允许的空白/控制符：换行、回车、制表、退格、换页、ESC
                if (b == '\n' || b == '\r' || b == '\t' || b == '\b' || b == '\f' || b == 0x1B) {
                    continue;
                }
                // NUL 检测：补充初始扫描窗口（64KB）未能覆盖的区域
                if (b == 0x00) {
                    return true;
                }
                // UTF-8 多字节高位字节
                if (b >= 0x80) {
                    continue;
                }
                // 其余控制字符 (0x01-0x07, 0x0B, 0x0E-0x1F 等)
                if (b < 0x20) {
                    suspicious++;
                }
            }
        }

        if (checked == 0) return false;

        // 第3关：自适应阈值
        int suspiciousPct = suspicious * 100 / checked;
        int printablePct = printable * 100 / checked;

        // 规则1：长段连续可打印文本 → 极大可能是文本（如日志、编译输出）
        if (maxPrintableRun > 120) {
            return suspiciousPct > 50;
        }

        // 规则2：可打印字符占优 → 文本性较强
        if (printablePct > 70) {
            return suspiciousPct > 45;
        }

        // 规则3：默认阈值（兼容原逻辑）
        return suspiciousPct > 30;
    }

    /**
     * 二进制输出摘要：尝试解码为文本展示；若不可读，则按文件类型 + hex 预览降级。
     *
     * <p>优化策略：<ul>
     *   <li>解码后文本可读性检测：若 &gt; 60% 字节可解码为可读文本且存在较长连续段，直接降级展示</li>
     *   <li>Magic bytes 文件类型识别：对真正二进制告诉用户是什么文件类型（class/jar/png 等）</li>
     * </ul></p>
     */
    private static String summarizeBinaryOutput(byte[] bytes) {
        // 尝试1：解码为文本，检查可读性
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        int scanLen = Math.min(decoded.length(), 10000);
        int printableCount = 0;
        int maxRun = 0, curRun = 0;

        for (int i = 0; i < scanLen; i++) {
            char c = decoded.charAt(i);
            if (c >= 0x20 && c <= 0x7E || c == '\n' || c == '\r' || c == '\t') {
                printableCount++;
                curRun++;
                maxRun = Math.max(maxRun, curRun);
            } else if (Character.isLetter(c) || Character.isDigit(c) || c == ' ') {
                printableCount++;
                curRun++;
                maxRun = Math.max(maxRun, curRun);
            } else {
                curRun = 0;
            }
        }

        int printablePct = scanLen > 0 ? printableCount * 100 / scanLen : 0;
        if (printablePct > 60 && maxRun > 50) {
            int total = decoded.length();
            String preview;
            if (total > 6000) {
                preview = decoded.substring(0, 3000)
                        + "\n... [中间省略 " + (total - 6000) + " 字符] ...\n"
                        + decoded.substring(total - 3000);
            } else {
                preview = decoded;
            }
            return "[注意：输出中检测到二进制特征，但大部分内容可读，已尝试解码显示]\n"
                    + preview
                    + "\n---\n"
                    + "(原始大小: " + bytes.length + " 字节)";
        }

        // 尝试2：检查 magic bytes 识别文件类型
        String fileType = detectFileType(bytes);

        // 默认 fallback：hex 预览 + 文件类型说明
        StringBuilder hex = new StringBuilder();
        int previewLen = Math.min(bytes.length, BINARY_PREVIEW_BYTES);
        for (int i = 0; i < previewLen; i++) {
            hex.append(String.format("%02x ", bytes[i] & 0xFF));
        }

        StringBuilder msg = new StringBuilder();
        msg.append("[检测到二进制输出，已省略乱码正文]\n");
        msg.append("约 ").append(bytes.length).append(" 字节");
        if (fileType != null) {
            msg.append("，识别类型: ").append(fileType);
        }
        msg.append("，前 ").append(previewLen).append(" 字节: ").append(hex.toString().trim());
        msg.append("\n提示：该命令的输出疑似二进制内容");
        if (fileType != null) {
            msg.append("（").append(fileType).append("）");
        }
        msg.append("。若需查看详细内容，请改用对应的工具（如 jar tf、unzip -l、javap、file、xxd 等）");
        msg.append("或先做合适的解码（或解压）再输出。");

        return msg.toString();
    }

    /**
     * 通过魔数（magic bytes）识别常见文件类型。
     */
    private static String detectFileType(byte[] bytes) {
        if (bytes.length < 4) return null;
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;

        if (b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE) return "JAVA_CLASS";
        if (b0 == 0x50 && b1 == 0x4B) {
            if (b2 == 0x03 && b3 == 0x04) return "ZIP/JAR/WAR";
            if (b2 == 0x05 && b3 == 0x06) return "ZIP (empty)";
            if (b2 == 0x07 && b3 == 0x08) return "ZIP (spanned)";
            return "ZIP";
        }
        if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) return "ELF";
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "PNG";
        if (b0 == 0xFF && b1 == 0xD8) return "JPEG";
        if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return "PDF";
        if (b0 == 0xD0 && b1 == 0xCF) return "DOC/XLS (OLE2)";
        if (b0 == 0x1F && b1 == 0x8B) return "GZIP";
        if (b0 == 0x42 && b1 == 0x5A && b2 == 0x68) return "BZIP2";
        if (b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58) return "XZ";
        if (b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21) return "RAR";
        if (b0 == 0x00 && b1 == 0x61 && b2 == 0x73 && b3 == 0x6D) return "WASM";
        return null;
    }

    /**
     * 面向 LLM 的头尾保留截断：超限时保留首尾片段，中间插入显式占位。
     */
    static String truncateForLlm(String text, int maxOutputChars) {
        if (text == null || text.length() <= maxOutputChars) {
            return text;
        }

        int total = text.length();
        // 预留占位说明的空间，头尾各占一半
        int budget = Math.max(0, maxOutputChars);
        int headLen = budget / 2;
        int tailLen = budget - headLen;

        String head = text.substring(0, headLen);
        String tail = text.substring(total - tailLen);
        int omitted = total - headLen - tailLen;

        return head
                + "\n... [输出过大已截断：总计 " + total + " 字符，省略中间 " + omitted
                + " 字符，仅保留首尾。如需完整内容，请用 read 工具分页读取，"
                + "或将输出重定向到文件后再处理] ...\n"
                + tail;
    }
}
