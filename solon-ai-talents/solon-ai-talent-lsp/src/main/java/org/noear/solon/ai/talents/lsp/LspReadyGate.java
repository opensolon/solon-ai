/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「项目模型是否已建好」的就绪门禁。
 *
 * <p>存在的理由：{@code initialize} 返回 ≠ 可以采信诊断。jdtls 收到 {@code initialize} 后才开始
 * 导入项目（多模块 Maven 仓库要数十秒到分钟级），这期间它会把每个文件当作「没有 classpath 的
 * 孤立源文件」来分析，并真的推送一批 {@code publishDiagnostics}：连 {@code AtomicBoolean} 这种
 * JDK 类型、同文件里的字段都会报 “cannot be resolved”。若照单全收注入到工具输出，模型就会
 * 去修一堆根本不存在的问题——这是 Java LSP 假报错的第二大来源。
 *
 * <p>因此在收到明确的就绪信号（jdtls 的 {@code language/status: ServiceReady}）之前，一律按
 * 「本轮无结论」处理：宁可暂时拿不到诊断，也不能给出错误结论。
 *
 * <p>兜底：超过 {@link #timeoutMs} 仍未收到就绪信号则视为就绪。永久等待意味着某些服务器版本
 * 不发这个通知时 Java 诊断彻底静默，那比偶发假错更难排查。
 *
 * @author noear
 * @since 4.1
 */
final class LspReadyGate {
    private static final Logger LOG = LoggerFactory.getLogger(LspReadyGate.class);

    private final String serverName;

    /**
     * 是否需要门禁：只对已知「导入期会推假诊断」的服务器开启，其它服务器一律视为即时就绪
     */
    private final boolean gated;

    private final long timeoutMs;
    private final long startedAt = System.currentTimeMillis();

    private final Object monitor = new Object();
    private volatile boolean ready;

    LspReadyGate(String serverName, boolean gated, long timeoutMs) {
        this.serverName = serverName;
        this.gated = gated;
        this.timeoutMs = timeoutMs;
        this.ready = (gated == false);
    }

    boolean isGated() {
        return gated;
    }

    /**
     * 是否可以采信该服务器的分析结果（诊断、导航）
     */
    boolean isReady() {
        if (ready) {
            return true;
        }
        if (System.currentTimeMillis() - startedAt >= timeoutMs) {
            markReady("no ready signal within " + timeoutMs + "ms, assuming ready");
            return true;
        }
        return false;
    }

    /**
     * 等待就绪，最长 {@code waitMs}
     *
     * @return 是否已就绪
     */
    boolean await(long waitMs) {
        if (isReady()) {
            return true;
        }

        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        synchronized (monitor) {
            while (ready == false) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    //别忘了兜底判定：等待期内可能刚好越过总预算
                    return isReady();
                }
                try {
                    monitor.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 标记就绪（幂等）
     *
     * @param reason 就绪依据，仅用于日志
     */
    void markReady(String reason) {
        if (ready) {
            return;
        }

        synchronized (monitor) {
            if (ready) {
                return;
            }
            ready = true;
            monitor.notifyAll();
        }

        LOG.info("LSP server '{}' is ready for analysis after {}ms ({})",
                serverName, System.currentTimeMillis() - startedAt, reason);
    }
}
