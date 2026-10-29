/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.runtime;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行桥 —— 定时任务(loop)与目标(goal)两个引擎共用的执行通道。
 *
 * <p>职责单一：把「某一轮执行的 prompt」交给宿主注册的执行器（CLI / Web / Desktop / ACP），
 * 并取回本轮的执行结果。两个引擎各自持有同一实例，互不 import，
 * 因此 goal 的独立性与 loop 的纯定时化可以并存。</p>
 *
 * <p>结果中的 token 消耗与工具证据来自会话 attrs（由各入口在跑完一轮后写入），
 * 这里只做读取与清理，不持有任何业务状态。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class ExecutionBridge {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionBridge.class);

    /** 单轮结果中由宿主写入、此处消费的会话属性。 */
    public static final String ATTR_LAST_TOTAL_TOKENS = "_loop_last_total_tokens";
    public static final String ATTR_LAST_HAS_TOOL_CALLS = "_loop_last_has_tool_calls";

    private final HarnessEngine engine;
    private volatile List<TaskHandler> taskHandlers = new CopyOnWriteArrayList<>();
    private volatile List<BusyChecker> busyCheckers = new CopyOnWriteArrayList<>();

    /** 任务处理者：执行一轮 prompt，返回最终文本（null 表示本入口不认领）。 */
    @FunctionalInterface
    public interface TaskHandler {
        String handle(String sessionId, String prompt, String agentName);
    }

    /** 繁忙检测者：会话正在执行其它任务时应跳过本轮。 */
    @FunctionalInterface
    public interface BusyChecker {
        boolean isBusy(String sessionId);
    }

    public ExecutionBridge(HarnessEngine engine) {
        this.engine = engine;
    }

    public void addTaskExecutor(TaskHandler executor) {
        if (executor != null) {
            this.taskHandlers.add(executor);
        }
    }

    public void addBusyChecker(BusyChecker busyChecker) {
        if (busyChecker != null) {
            this.busyCheckers.add(busyChecker);
        }
    }

    /** 会话是否繁忙（任一检查器认定繁忙即为繁忙）。 */
    public boolean isBusy(String sessionId) {
        for (BusyChecker checker : busyCheckers) {
            try {
                if (checker.isBusy(sessionId)) {
                    return true;
                }
            } catch (Throwable e) {
                LOG.debug("BusyChecker failed for session '{}': {}", sessionId, e.getMessage());
            }
        }
        return false;
    }

    /**
     * 执行一轮（不含任何业务判定）。
     *
     * @return 执行结果；没有任何处理器认领时返回 null
     */
    public RoundResult execute(String sessionId, String effectivePrompt, String agentName) {
        for (TaskHandler taskExecutor : taskHandlers) {
            String result = taskExecutor.handle(sessionId, effectivePrompt, agentName);
            if (result != null) {
                return toRoundResult(sessionId, result);
            }
        }
        return null;
    }

    /** 是否已有执行器注册（无执行器时调用方不应启动轮次）。 */
    public boolean hasTaskHandler() {
        return !taskHandlers.isEmpty();
    }

    private RoundResult toRoundResult(String sessionId, String result) {
        long tokensUsed = 0;
        Boolean hasToolCalls = null;
        try {
            AgentSession session = engine.getSession(sessionId);
            Object val = session.attrs().get(ATTR_LAST_TOTAL_TOKENS);
            if (val instanceof Number) {
                tokensUsed = ((Number) val).longValue();
            }
            Object toolEvidence = session.attrs().remove(ATTR_LAST_HAS_TOOL_CALLS);
            if (toolEvidence instanceof Boolean) {
                hasToolCalls = (Boolean) toolEvidence;
            }
        } catch (Exception e) {
            // fallback: 使用文本估算
        }

        if (tokensUsed > 0 || hasToolCalls != null) {
            long effectiveTokens = tokensUsed > 0 ? tokensUsed : Math.max(1, result.length() / 4);
            return RoundResult.of(hasToolCalls != null
                            ? hasToolCalls
                            : result.length() > 20 && !result.startsWith("error:"),
                    effectiveTokens, result);
        }
        return RoundResult.ofText(result);
    }

    /**
     * 按 sessionId 解析会话（供 goal 等引擎复用，避免各自持有一份 resolver）。
     *
     * @return 会话；引擎未就绪或解析失败时返回 null
     */
    public AgentSession sessionOf(String sessionId) {
        if (sessionId == null || engine == null) return null;
        try {
            return engine.getSession(sessionId);
        } catch (Throwable e) {
            LOG.debug("resolve session '{}' failed: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 当前轮次的工具证据标记。
     *
     * @return null 表示当前入口未提供证据追踪；false/true 表示已启用追踪
     */
    public Boolean currentRoundHasActionEvidence(String sessionId) {
        try {
            Object value = engine.getSession(sessionId).attrs().get(ATTR_LAST_HAS_TOOL_CALLS);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
