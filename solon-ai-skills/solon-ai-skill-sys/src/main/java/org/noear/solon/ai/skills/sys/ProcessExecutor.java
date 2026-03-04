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
package org.noear.solon.ai.skills.sys;

import org.noear.solon.core.util.RunUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
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

    private int maxOutputSize = 1024 * 1024; // 默认 1MB
    private int timeoutSeconds = 120;        // 默认 120s
    private Charset scriptCharset = StandardCharsets.UTF_8;
    private Charset outputCharset = StandardCharsets.UTF_8;

    public int getMaxOutputSize() {
        return maxOutputSize;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
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

    /**
     * 配置超时时间（秒）
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public void setScriptCharset(Charset scriptCharset) {
        this.scriptCharset = scriptCharset;
    }

    public void setOutputCharset(Charset outputCharset) {
        this.outputCharset = outputCharset;
    }

    /**
     * 执行代码脚本（持久化为临时文件后执行）
     */
    public String executeCode(Path rootPath, String code, String cmd, String ext, Map<String, String> envs, Consumer<String> onOutput) {
        Path tempScript = null;
        try {
            // 1. 持久化脚本
            tempScript = Files.createTempFile(rootPath, "_script_", ext);
            Files.write(tempScript, code.getBytes(scriptCharset));

            // 2. 构建完整命令（处理带空格的命令字符串）
            List<String> fullCmd = new ArrayList<>(Arrays.asList(cmd.split("\\s+")));
            fullCmd.add(tempScript.toAbsolutePath().toString());

            return executeCmd(rootPath, fullCmd, envs, onOutput);
        } catch (Exception e) {
            LOG.error("Code execution failed", e);
            return "代码执行失败: " + e.getMessage();
        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 执行完整命令，支持实时输出回调
     */
    public String executeCmd(Path rootPath, List<String> fullCmd, Map<String, String> envs, Consumer<String> onOutput) {
        try {
            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.directory(rootPath.toFile());
            pb.redirectErrorStream(true);

            if (envs != null) {
                pb.environment().putAll(envs);
            }

            Process process = pb.start();

            // 1. 异步读取输出
            CompletableFuture<String> outputFuture = RunUtil.async(() -> {
                StringBuilder sb = new StringBuilder();
                try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), outputCharset)) {
                    char[] buffer = new char[4096];
                    int n;
                    while ((n = reader.read(buffer)) != -1) {

                        if (onOutput != null) {
                            String fragment = new String(buffer, 0, n);
                            onOutput.accept(fragment);
                        }

                        if (sb.length() + n < maxOutputSize) {
                            sb.append(buffer, 0, n);
                        } else {
                            int remaining = maxOutputSize - sb.length();
                            if (remaining > 0) {
                                sb.append(buffer, 0, remaining);
                            }
                            sb.append("\n... [输出已截断]");
                            process.destroyForcibly();
                            break;
                        }
                    }
                } catch (IOException e) {
                    LOG.debug("Stream reading interrupted: {}", e.getMessage());
                }
                return sb.toString();
            });

            // 2. 超时控制
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "执行超时：运行时间超过 " + timeoutSeconds + " 秒。";
            }

            // 3. 获取输出结果
            String result = outputFuture.get(1, TimeUnit.SECONDS).trim();
            return result.isEmpty() ? "执行成功" : result;

        } catch (Exception e) {
            LOG.error("Process execution failed", e);
            return "系统失败: " + e.getMessage();
        }
    }
}