/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.intercept.FlowInterceptor;

/**
 * Team 拦截器
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamInterceptor extends FlowInterceptor {
    /**
     * 1. 团队整体开始 (在 TeamAgent.call 开始时)
     */
    default void onTeamStart(TeamTrace trace) {}

    /**
     * 2. 监管员询问前 (这是最关键的拦截点：用于 Loop 检测、预算检查、人工审批)
     * @return true: 继续调用 LLM 进行决策; false: 终止本次决策执行
     */
    default boolean shouldSupervisorContinue(TeamTrace trace) {
        return true;
    }

    /**
     * 3. 监管员决策后 (LLM 返回了 decision，但还没解析成具体的 Agent 路由时)
     * 灵感来自 onThought。可以用来修正或记录 LLM 的原始思考过程。
     */
    default void onSupervisorDecision(TeamTrace trace, String decision) {}

    /**
     * 4. 成员智能体执行前 (不仅仅是 Start，而是具有拦截能力的准入)
     * @return true: 允许该 Agent 执行; false: 跳过该 Agent 或终止
     */
    default boolean shouldAgentContinue(TeamTrace trace, Agent agent) {
        return true;
    }

    /**
     * 5. 成员智能体执行后 (结果已存入 trace)
     */
    default void onAgentEnd(TeamTrace trace, Agent agent) {}

    /**
     * 6. 团队整体结束
     */
    default void onTeamEnd(TeamTrace trace) {}
}
