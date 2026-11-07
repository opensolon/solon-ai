/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.sandbox.SandboxManager;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;

/**
 * 进程级 OS 沙盒引导（多引擎进程用）
 *
 * <p>{@code SandboxManager} 是 JVM 单例：网络代理、域名白名单只按初始化时的配置全局生效
 * （单次调用传入的配置只能收窄文件系统，网络白名单不按调用区分）。一个进程里有多个引擎时，
 * 由宿主在启动时调用一次 {@link #initOnce}，并让各引擎关闭自行初始化
 * （{@link TerminalTalent#setSandboxSelfBootstrap(boolean)}）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SandboxBootstrap {
    private SandboxBootstrap() {
    }

    /**
     * 初始化进程级沙盒（幂等：已初始化时直接返回 false）
     *
     * @param config 进程级沙盒配置（网络白名单、代理等）；文件系统部分会被每次调用的动态配置覆盖
     * @return 本次是否执行了初始化
     * @throws IllegalStateException 初始化失败（已复位，可重试）
     */
    public static synchronized boolean initOnce(SandboxRuntimeConfig config) {
        if (SandboxManager.isSandboxingEnabled()) {
            return false;
        }
        try {
            SandboxManager.initialize(config, null);
            return true;
        } catch (Exception e) {
            try {
                SandboxManager.reset();
            } catch (Exception ignored) {
                //保留原始异常
            }
            throw new IllegalStateException("OS sandbox initialization failed", e);
        }
    }

    /**
     * 进程级沙盒是否已就绪
     */
    public static boolean isReady() {
        return SandboxManager.isSandboxingEnabled();
    }
}
