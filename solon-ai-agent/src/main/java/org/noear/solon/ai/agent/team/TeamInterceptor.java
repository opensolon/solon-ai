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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.flow.intercept.FlowInterceptor;

/**
 * Team 拦截器
 * <p>提供对团队协作智能体（TeamAgent）执行全生命周期的监控与干预能力。
 * 包括团队起止、监管员决策审计以及成员智能体的准入控制。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamInterceptor extends FlowInterceptor {

    /**
     * 团队生命周期：团队整体任务开始时触发
     * <p>常用于初始化团队上下文或记录多智能体协作任务的起点。</p>
     */
    default void onTeamStart(TeamTrace trace) {}

    /**
     * 决策控制：在监管员（Supervisor）发起决策请求前触发
     * <p>这是控制协作成本和防止团队死循环的核心拦截点。可用于：</p>
     * <ul>
     * <li>1. 预算检查：判断当前 Token 消耗是否超出阈值</li>
     * <li>2. 循环检测：判断是否在反复调度同一个成员</li>
     * <li>3. 人工审批：在特定条件下暂停任务，等待人工确认</li>
     * </ul>
     * @return true: 继续调用 LLM 进行决策; false: 终止本次决策并结束团队任务
     */
    default boolean shouldSupervisorContinue(TeamTrace trace) {
        return true;
    }

    /**
     * 模型推理生命周期：发起监管员模型请求前触发
     * <p>常用于对监管员的 Prompt 进行动态调整、注入特定约束或进行 Token 预审计。</p>
     */
    default void onModelStart(TeamTrace trace, ChatRequestDesc req) {
    }

    /**
     * 模型推理生命周期：监管员模型响应后，解析逻辑执行前触发
     * <p>可用于拦截监管员的原始回复，执行合规性检查或原始数据记录。</p>
     */
    default void onModelEnd(TeamTrace trace, ChatResponse resp) {
    }

    /**
     * 协作生命周期：监管员决策产生后触发
     * <p>此时模型已完成思考（Thought），但尚未将决策转换为具体的 Agent 执行路由。
     * 可用于修正、记录或提取决策背后的思考链路（灵感来自 onThought）。</p>
     */
    default void onSupervisorDecision(TeamTrace trace, String decision) {}

    /**
     * 成员调度生命周期：具体成员智能体（Member Agent）执行前触发
     * <p>提供对特定智能体调度的准入拦截逻辑。</p>
     * @return true: 允许该 Agent 执行; false: 跳过该 Agent 的本次执行
     */
    default boolean shouldAgentContinue(TeamTrace trace, Agent agent) {
        return true;
    }

    /**
     * 成员调度生命周期：具体成员智能体执行完成后触发
     * <p>此时成员执行的结果已合并入团队上下文 {@link TeamTrace}。</p>
     */
    default void onAgentEnd(TeamTrace trace, Agent agent) {}

    /**
     * 团队生命周期：团队整体任务结束时触发
     */
    default void onTeamEnd(TeamTrace trace) {}
}