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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
     */
    public boolean isCommandAvailable(String cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            process = pb.start();

            try (java.io.InputStream in = process.getInputStream()) {
                byte[] buf = new byte[1024];
                while (in.read(buf) != -1) ;
            }

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


    public String probePythonCommand() {
        if (isCommandAvailable("python3")) {
            return "python3";
        }
        if (isCommandAvailable("python")) {
            return "python";
        }
        return null; // 或者返回 ""，表示没找到
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
     * 执行代码脚本（持久化为临时文件后执行）
     */
    public String executeCode(Path rootPath, String code, String cmd, String ext, Map<String, String> envs, Integer timeoutMs, Integer maxOutputChars, Consumer<String> onOutput) {
        if (Assert.isEmpty(cmd)) {
            return "执行失败: 未找到可用的运行命令（Command not found）";
        }

        Path tempScript = null;
        try {
            // 1. 持久化脚本（Windows .bat 文件需前置 chcp 65001 以确保 UTF-8 输出）
            String finalCode = code;
            if (".bat".equals(ext)) {
                finalCode = "@chcp 65001 > nul\r\n" + code;
            }
            tempScript = Files.createTempFile(rootPath, "_script_", ext);
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

            if (envs != null) {
                pb.environment().putAll(envs);
            }

            Process process = pb.start();

            // 1. 异步读取输出（在字节层收集，便于可靠的二进制探测）
            CompletableFuture<byte[]> outputFuture = RunUtil.async(() -> {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {

                        if (onOutput != null) {
                            // 实时回调按 outputCharset 解码片段（流式场景）
                            onOutput.accept(new String(buffer, 0, n, outputCharset));
                        }

                        if (buf.size() + n <= maxOutputSize) {
                            buf.write(buffer, 0, n);
                        } else {
                            int remaining = maxOutputSize - buf.size();
                            if (remaining > 0) {
                                buf.write(buffer, 0, remaining);
                            }
                            // 物理上限兜底：防止超大输出撑爆内存
                            process.destroyForcibly();
                            break;
                        }
                    }
                } catch (IOException e) {
                    LOG.debug("Stream reading interrupted: {}", e.getMessage());
                }
                return buf.toByteArray();
            });

            // 2. 超时控制
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "执行超时：运行时间超过 " + timeoutMs + " 毫秒。";
            }

            // 3. 获取原始字节输出
            byte[] raw = outputFuture.get(1, TimeUnit.SECONDS);

            // 二进制输出降级：在字节层探测，避免乱码塞满上下文（如 cat 某个 jar/class/图片）
            if (isLikelyBinary(raw)) {
                return summarizeBinaryOutput(raw);
            }

            String result = new String(raw, outputCharset).trim();
            if (result.isEmpty()) {
                return "执行成功";
            }

            // 面向 LLM 的输出上限：头尾保留截断
            return truncateForLlm(result, normalizeMaxOutputChars(maxOutputChars));

        } catch (Throwable e) {
            LOG.error("Process execution failed", e);
            return "系统失败: " + e.getMessage();
        }
    }

    private static int normalizeMaxOutputChars(Integer maxOutputChars) {
        if (maxOutputChars == null || maxOutputChars <= 0) {
            return DEFAULT_LLM_OUTPUT_CHARS;
        }
        return maxOutputChars;
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
