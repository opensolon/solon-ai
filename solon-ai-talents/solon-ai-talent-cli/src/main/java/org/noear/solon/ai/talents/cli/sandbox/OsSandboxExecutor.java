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
package org.noear.solon.ai.talents.cli.sandbox;

import java.nio.file.Path;
import java.util.Map;

/**
 * OS 级沙盒执行器接口
 *
 * <p>在命令执行前将其包装在 OS 级沙盒中，提供内核级强制隔离。
 * 即使 LLM 构造了绕过 Java 层校验的命令，OS 内核也会拦截。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public interface OsSandboxExecutor {
    /**
     * 包装命令，使其在 OS 级沙盒中执行
     *
     * @param command  原始命令（已过 translateCommandToEnv 翻译）
     * @param workPath 工作目录
     * @param envs     环境变量
     * @return 包装后的命令字符串
     */
    String wrapCommand(String command, Path workPath, Map<String, String> envs);

    /**
     * 探测当前平台是否支持此沙盒
     */
    boolean isAvailable();

    /**
     * 设置沙盒配置（可选，用于支持读写分离等高级功能）
     */
    default void setConfig(SandboxConfig config) {}

    /**
     * 设置违规存储（可选，用于支持违规监控）
     */
    default void setViolationStore(SandboxViolationStore store) {}
}
