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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 团队协作协议 (Team Protocol)
 *
 * <p>核心职责：定义多智能体协作的拓扑结构、指令干预逻辑与路由治理机制。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamProtocol extends NonSerializable {
    static final Logger LOG = LoggerFactory.getLogger(TeamProtocol.class);

    /**
     * 获取协议唯一标识（如 SEQUENTIAL, SWARM, HIERARCHICAL）
     */
    String name();

    // --- [阶段 1：静态构建] ---

    /**
     * 构建协作拓扑图（定义节点间的连接关系）
     */
    void buildGraph(GraphSpec spec);

    // --- [阶段 2：成员 Agent 运行期] ---

    /**
     * 注入协议专属工具（如转交、抄送等控制工具）
     */
    default void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) { }

    /**
     * 注入 Agent 行为约束指令（定义角色规范）
     */
    default void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) { }

    /**
     * 动态生成 Agent 提示词（在此处处理上下文衔接或状态同步）
     */
    default Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        return originalPrompt;
    }

    /**
     * 解析并适配 Agent 输出（在记录轨迹前进行内容转换）
     */
    default String resolveAgentOutput(TeamTrace trace, Agent agent, String content) {
        return content;
    }

    /**
     * Agent 节点执行结束回调
     */
    default void onAgentEnd(TeamTrace trace, Agent agent) { }

    // --- [阶段 3：主管 Supervisor 治理] ---

    /**
     * 注入主管静态系统指令（定义全局调度准则）
     */
    default void injectSupervisorInstruction(Locale locale, StringBuilder sb) { }

    /**
     * 注入主管动态决策指令（如实时进度、环境感知）
     */
    default void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) { }

    /**
     * 注入主管决策上下文（如待办事项、黑板状态）
     */
    default void prepareSupervisorContext(FlowContext context, TeamTrace trace, StringBuilder sb) { }

    /**
     * 决策准入干预（返回 false 则跳过 LLM 智能决策，用于固定流程）
     */
    default boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        return true;
    }

    /**
     * 解析路由目标（将决策文本语义化为节点 ID）
     * @return 目标节点 ID；返回 null 则由系统默认逻辑解析
     */
    default String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        return null;
    }

    /**
     * 路由决策预干预（允许协议在此处强行改变跳转方向）
     */
    default boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        return true;
    }

    /**
     * 确定路由目标后的最终回调
     */
    default void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Protocol [{}] routing to: {}", name(), nextAgent);
        }
    }

    // --- [阶段 4：生命周期销毁] ---

    /**
     * 协作任务结束清理
     */
    default void onTeamFinished(FlowContext context, TeamTrace trace) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Protocol [{}] session finished for trace: {}", name(), trace.getConfig().getTraceKey());
        }
    }
}