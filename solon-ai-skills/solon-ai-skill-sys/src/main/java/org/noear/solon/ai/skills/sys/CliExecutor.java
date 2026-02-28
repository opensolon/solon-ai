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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
public class CliExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(CliExecutor.class);

    private final Path rootPath;

    private int maxOutputSize = 1024 * 1024; // 默认 1MB
    private int timeoutSeconds = 30;         // 默认 30s
    private Charset scriptCharset = StandardCharsets.UTF_8;
    private Charset outputCharset = StandardCharsets.UTF_8;

    public CliExecutor(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir();
    }

    public CliExecutor(Path workDir) {
        this.rootPath = workDir;
        ensureDir();
    }

    public Path getRootPath() {
        return rootPath;
    }

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

    public String execute(String code, String cmd, String ext, Map<String, String> envs) {
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile(rootPath, "ai_script_", ext);
            Files.write(tempScript, code.getBytes(scriptCharset));

            // 1. 优化：更稳健的命令构建
            List<String> fullCmd = new ArrayList<>(Arrays.asList(cmd.split("\\s+")));
            fullCmd.add(tempScript.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.directory(rootPath.toFile());
            pb.redirectErrorStream(true);

            if (envs != null) {
                pb.environment().putAll(envs);
            }

            Process process = pb.start();


            // 2. 优化：改用字符缓冲区读取，防止单行过长撑爆内存
            CompletableFuture<String> outputFuture = RunUtil.async(() -> {
                StringBuilder sb = new StringBuilder();
                try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), outputCharset)) {
                    char[] buffer = new char[4096];
                    int n;
                    while ((n = reader.read(buffer)) != -1) {
                        if (sb.length() + n < maxOutputSize) {
                            sb.append(buffer, 0, n);
                        } else {
                            int remaining = maxOutputSize - sb.length();
                            if (remaining > 0) {
                                sb.append(buffer, 0, remaining);
                            }
                            sb.append("\n... [输出已截断]");
                            process.destroyForcibly(); // 强制结束
                            break;
                        }
                    }
                } catch (IOException e) {
                    LOG.debug("Stream reading interrupted: {}", e.getMessage());
                }
                return sb.toString();
            });


            // 3. 超时控制
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时：运行时间超过 " + timeoutSeconds + " 秒。";
            }

            // 等待读取线程结束（给一点点缓冲时间处理最后剩余的 buffer）
            String result = outputFuture.get(1, TimeUnit.SECONDS).trim();
            return result.isEmpty() ? "执行成功" : result;

        } catch (Exception e) {
            LOG.error("Execution failed", e); // 修正拼写
            return "异常失败: " + e.getMessage();
        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void ensureDir() {
        try {
            if (!Files.exists(rootPath)) {
                Files.createDirectories(rootPath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize work directory: " + rootPath, e);
        }
    }
}