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

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * 外部进程执行基类
 *
 * <p>提供通用的代码持久化、子进程启动、标准输出捕获及执行超时控制。
 * 具备输出截断保护机制，防止大数据量输出导致内存溢出。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public abstract class AbsProcessSkill extends AbsSkill {

    protected final Path rootPath;
    protected final CliExecutor executor = new CliExecutor();

    public AbsProcessSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir();
    }

    public AbsProcessSkill(Path workDir) {
        this.rootPath = workDir;
        ensureDir();
    }

    /**
     * 配置最大输出大小（字节）
     */
    public void setMaxOutputSize(int maxOutputSize) {
        executor.setMaxOutputSize(maxOutputSize);
    }

    /**
     * 配置超时时间（秒）
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        executor.setTimeoutSeconds(timeoutSeconds);
    }

    protected String runCode(String code, String cmd, String ext, Map<String, String> envs) {
        return executor.execute(rootPath, code, cmd, ext, envs);
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