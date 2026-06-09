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

import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * OS 级沙盒执行器工厂（自动探测平台）
 *
 * @author noear
 * @since 3.9.1
 */
public class OsSandboxExecutorFactory {
    private static final Logger LOG = LoggerFactory.getLogger(OsSandboxExecutorFactory.class);

    /**
     * 创建平台适配的沙盒执行器（无配置）
     */
    public static OsSandboxExecutor create() {
        return create(null);
    }

    /**
     * 创建平台适配的沙盒执行器（带配置）
     */
    public static @NonNull OsSandboxExecutor create(SandboxConfig config) {
        String os = System.getProperty("os.name").toLowerCase();

        // macOS: 尝试 sandbox-exec
        if (os.contains("mac")) {
            MacOsSandboxExecutor mac = new MacOsSandboxExecutor();
            if (mac.isAvailable()) {
                if (config != null) mac.setConfig(config);
                LOG.info("OS sandbox: MacOsSandboxExecutor (sandbox-exec/Seatbelt)");
                return mac;
            }
        }

        // Linux: 尝试 bubblewrap
        if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            LinuxSandboxExecutor linux = new LinuxSandboxExecutor();
            if (linux.isAvailable()) {
                if (config != null) linux.setConfig(config);
                LOG.info("OS sandbox: LinuxSandboxExecutor (bubblewrap)");
                return linux;
            }
        }

        // Fallback: ulimit
        LOG.info("OS sandbox: UlimitFallbackExecutor (ulimit-based resource limits)");
        return new UlimitFallbackExecutor();
    }

    /**
     * 检测命令是否可用
     */
    static boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean exited = p.waitFor(3, TimeUnit.SECONDS);
            p.destroyForcibly();
            return exited && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
