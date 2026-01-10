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
import org.noear.solon.lang.Preview;

/**
 * 团队协作拦截器 (Team Interceptor)
 * <p>
 * 提供对 TeamAgent 协作全生命周期的监控与干预能力。
 * 开发者可以通过实现此接口，在团队初始化、模型决策、成员执行等关键环节注入业务逻辑。
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamInterceptor extends FlowInterceptor {

    // --- [维度 1：团队生命周期 (Team Level)] ---

    /**
     * 协作开始：团队任务启动时触发
     * <p>常用于初始化全局上下文、分配 Trace ID 或开启性能埋点。</p>
     */
    default void onTeamStart(TeamTrace trace) {}

    /**
     * 协作结束：团队任务整体完成（或被强行熔断）后触发
     * <p>此时可以进行汇总统计、持久化协作日志或清理资源。</p>
     */
    default void onTeamEnd(TeamTrace trace) {}


    // --- [维度 2：决策生命周期 (Supervisor/Model Level)] ---

    /**
     * 决策准入：在指挥员 (Supervisor) 发起逻辑判断前触发
     * <p>这是防止协作失控的“一级刹车”，可用于检查迭代深度、Token 余额或强制人工干预。</p>
     *
     * @return true: 继续决策; false: 立即中止团队协作并设为结束状态
     */
    default boolean shouldSupervisorContinue(TeamTrace trace) {
        return true;
    }

    /**
     * 模型启动：指挥员发起模型请求 (LLM Call) 前触发
     * <p>允许动态修改 {@link ChatRequestDesc}，如注入临时变量、调整温度系数或追加协议约束。</p>
     */
    default void onModelStart(TeamTrace trace, ChatRequestDesc req) {
    }

    /**
     * 模型返回：指挥员获得模型原始响应后触发
     * <p>在决策文本解析前介入，可用于拦截原始数据流、审计模型输出或执行内容风控。</p>
     */
    default void onModelEnd(TeamTrace trace, ChatResponse resp) {
    }

    /**
     * 决策产出：指挥员完成语义解析并确定决策内容后触发
     * <p>此时模型已完成“思考”，但尚未跳转物理路由。可用于记录指挥员的调度意图（Decision Context）。</p>
     * * @param decision 经解析后的决策文本（通常是 Agent 名称或 finish 指令）
     */
    default void onSupervisorDecision(TeamTrace trace, String decision) {}


    // --- [维度 3：成员生命周期 (Member Agent Level)] ---

    /**
     * 成员准入：具体成员智能体被调度执行前触发
     * <p>提供细粒度的权限控制。例如：只允许特定 Agent 在特定条件下访问敏感工具。</p>
     *
     * @param agent 即将执行的目标智能体
     * @return true: 允许执行; false: 跳过该成员执行，重新回到决策环节
     */
    default boolean shouldAgentContinue(TeamTrace trace, Agent agent) {
        return true;
    }

    /**
     * 成员执行结束：具体成员智能体任务完成后触发
     * <p>此时该成员的输出已同步至 {@link TeamTrace} 的历史记录中，可用于结果后置处理。</p>
     *
     * @param agent 已完成执行的智能体
     */
    default void onAgentEnd(TeamTrace trace, Agent agent) {}
}