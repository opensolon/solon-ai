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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Preview;

/**
 * HITL (Human-in-the-Loop) 操作助手
 * <p>提供面向业务层的便捷 API，用于提交审批决策及获取挂起状态</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITL {
    /** 决策状态存储前缀 */
    public static final String DECISION_PREFIX = "_hitl_decision_";
    /** 最近一次被拦截的任务存储 Key */
    public static final String LAST_INTERVENED = "_last_intervened_";

    /**
     * 提交人工决策
     *
     * @param session  Agent 会话
     * @param toolName 工具名称
     * @param decision 决策实体（包含同意、拒绝理由或修正参数）
     */
    public static void submit(AgentSession session, String toolName, HITLDecision decision) {
        session.getSnapshot().put(DECISION_PREFIX + toolName, decision);
    }

    /**
     * 快捷批准工具执行
     */
    public static void approve(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.approve());
    }

    /**
     * 快捷拒绝工具执行（使用默认拒绝意见）
     */
    public static void reject(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.reject(null));
    }

    /**
     * 快捷拒绝工具执行（带具体意见）
     */
    public static void reject(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.reject(comment));
    }

    /**
     * 获取会话中当前挂起的任务信息
     *
     * @return 挂起的任务实体，若无挂起则返回 null
     */
    public static HITLTask getPendingTask(AgentSession session) {
        return session.getSnapshot().getAs(LAST_INTERVENED);
    }
}