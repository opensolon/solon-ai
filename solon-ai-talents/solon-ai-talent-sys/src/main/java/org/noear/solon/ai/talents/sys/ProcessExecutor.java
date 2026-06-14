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
package org.noear.solon.ai.talents.sys;

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
import java.util.ArrayList;
import java.util.Arrays;
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
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = new ProcessBuilder(isWindows ? Arrays.asList("where", cmd) : Arrays.asList("which", cmd));
            Process process = pb.start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Throwable e) {
            return false;
        }
    }


    public String probePythonCommand() {
        return isCommandAvailable("python3") ? "python3" : "python";
    }

    public String probeNodeCommand() {
        return isCommandAvailable("node") ? "node" : "nodejs";
    }

    /**
     * 执行代码脚本（持久化为临时文件后执行）
     */
    public String executeCode(Path rootPath, String code, String cmd, String ext, Map<String, String> envs, Integer timeoutMs, Consumer<String> onOutput) {
        return executeCode(rootPath, code, cmd, ext, envs, timeoutMs, null, onOutput);
    }

    /**
     * 执行代码脚本（持久化为临时文件后执行），支持面向 LLM 的输出字符上限。
     */
    public String executeCode(Path rootPath, String code, String cmd, String ext, Map<String, String> envs, Integer timeoutMs, Integer maxOutputChars, Consumer<String> onOutput) {
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
            List<String> fullCmd = new ArrayList<>(Arrays.asList(cmd.split("\\s+")));
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
     * 执行完整命令，支持实时输出回调
     */
    public String executeCmd(Path rootPath, List<String> fullCmd, Map<String, String> envs, Integer timeoutMs, Consumer<String> onOutput) {
        return executeCmd(rootPath, fullCmd, envs, timeoutMs, null, onOutput);
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
     * 被替换成可打印的 U+FFFD，导致字符串层探测失效（如 jar/class 漏判）。
     * 对 ANSI 转义（ESC，0x1B）及 UTF-8 多字节高位字节（&gt;= 0x80）做豁免，
     * 避免误伤带色彩的日志与中文等多字节文本。</p>
     */
    static boolean isLikelyBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }

        int sample = Math.min(bytes.length, BINARY_PROBE_SAMPLE);
        int suspicious = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0x00) {
                // NUL 是二进制的强特征，直接判定
                return true;
            }
            // 允许常见空白、ANSI 转义（ESC=0x1B）、以及 UTF-8 多字节高位字节
            if (b == '\n' || b == '\r' || b == '\t' || b == 0x1B || b >= 0x80) {
                continue;
            }
            if (b < 0x20) {
                suspicious++;
            }
        }

        return suspicious * 100 > sample * 30; // 控制字符占比 > 30%
    }

    /**
     * 二进制输出摘要：丢弃乱码正文，仅返回真实规模与首字节十六进制预览。
     */
    private static String summarizeBinaryOutput(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        int previewLen = Math.min(bytes.length, BINARY_PREVIEW_BYTES);
        for (int i = 0; i < previewLen; i++) {
            hex.append(String.format("%02x ", bytes[i] & 0xFF));
        }

        return "[检测到二进制输出，已省略乱码正文]\n"
                + "约 " + bytes.length + " 字节"
                + "，前 " + previewLen + " 字节: " + hex.toString().trim()
                + "\n提示：该命令的输出疑似二进制内容（如读取了 zip/class/图片等）。"
                + "若需查看其内容，请改用对应的工具（如 jar tf、unzip -l、javap、file、xxd 等）"
                + "或先做合适的解码（或解压）再输出。";
    }

    /**
     * 面向 LLM 的头尾保留截断：超限时保留首尾片段，中间插入显式占位。
     */
    static String truncateForLlm(String text, int maxOutputChars) {
        if (text == null || text.length() <= maxOutputChars) {
            return text;
        }

        int total = text.length();
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