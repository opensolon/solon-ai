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
 * 基于 ulimit 的轻量资源限制（Fallback 方案）
 *
 * <p>无文件系统隔离能力，但可防止资源耗尽。
 * 适用于无 sandbox-exec（Windows）或无 bwrap（未安装）的场景。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class UlimitFallbackExecutor implements OsSandboxExecutor {
    // 最大子进程数
    private static final int MAX_PROCESSES = 64;
    // 最大单文件大小（KB），100MB
    private static final long MAX_FILE_SIZE_KB = 100 * 1024;

    @Override
    public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
        return injectResourceLimits(command);
    }

    @Override
    public boolean isAvailable() {
        return true; // ulimit 始终可用（Unix 平台）
    }

    /**
     * 注入 ulimit 资源限制前缀
     */
    public static String injectResourceLimits(String command) {
        return String.format(
                "ulimit -u %d -f %d 2>/dev/null; ",
                MAX_PROCESSES, MAX_FILE_SIZE_KB
        ) + command;
    }
}
