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
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.flow.intercept.FlowInterceptor;
import org.noear.solon.lang.Preview;

/**
 * 团队协作拦截器 (Team Interceptor)
 * * <p>核心职责：提供对 TeamAgent 协作全生命周期的观察与干预能力。支持团队、决策、成员三个维度的切面注入。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamInterceptor extends FlowInterceptor, ChatInterceptor {

    // --- [维度 1：团队级 (Team Level)] ---

    /**
     * 团队协作开始
     */
    default void onTeamStart(TeamTrace trace) {}

    /**
     * 团队协作结束
     */
    default void onTeamEnd(TeamTrace trace) {}


    // --- [维度 2：决策级 (Supervisor Level)] ---

    /**
     * 决策准入校验（主管发起思考前）
     * * @return true: 继续执行; false: 熔断并中止协作
     */
    default boolean shouldSupervisorContinue(TeamTrace trace) {
        return true;
    }

    /**
     * 模型请求前置（LLM 调用前）
     * <p>常用于动态调整 Request 参数（如 Temperature, MaxTokens 等）。</p>
     */
    default void onModelStart(TeamTrace trace, ChatRequestDesc req) {}

    /**
     * 模型响应后置（LLM 返回后，解析前）
     * <p>常用于内容安全审计或原始 Token 统计。</p>
     */
    default void onModelEnd(TeamTrace trace, ChatResponse resp) {}

    /**
     * 决策结果输出（指令解析后）
     * * @param decision 经解析确定的目标 Agent 名称或终结指令
     */
    default void onSupervisorDecision(TeamTrace trace, String decision) {}


    // --- [维度 3：成员级 (Agent Level)] ---

    /**
     * 成员执行准入校验（Agent 运行前）
     * * @param agent 即将运行的智能体
     * @return true: 允许运行; false: 跳过并回滚至决策层
     */
    default boolean shouldAgentContinue(TeamTrace trace, Agent agent) {
        return true;
    }

    /**
     * 成员执行结束
     */
    default void onAgentEnd(TeamTrace trace, Agent agent) {}
}