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
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.NonSerializable;

import java.util.Locale;

/**
 * 团队协作协议（Team Collaboration Protocol）
 *
 * <p>定义 Agent 团队的协作模式（如 SEQUENTIAL, SWARM, HIERARCHICAL 等）。</p>
 * <p>核心职责包括：构建拓扑结构、动态工具注入、提示词定制以及对协作路由的精准干预。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamProtocol extends NonSerializable {
    /**
     * 获取协议唯一标识（如 "A2A", "Sequential"）
     */
    String name();

    /**
     * [阶段：构建期] 构建团队协作图的拓扑结构
     * <p>定义 Agent 节点间的逻辑链路，决定控制权的流转方向（如 A -> B 或 A -> Supervisor）。</p>
     *
     * @param spec 图规格定义器
     */
    void buildGraph(GraphSpec spec);

    /**
     * [阶段：执行前] 为执行中的智能体注入专属工具
     * <p>常用于注入协议相关的系统级工具（如：__transfer_to__）。</p>
     *
     * @param agent 当前执行的智能体
     * @param trace 运行轨迹上下文
     */
    default void injectAgentTools(Agent agent, ReActTrace trace) { }

    /**
     * [阶段：执行前] 为智能体注入系统指令（System Instruction）
     * <p>用于向 Agent 传达当前协议下的协作规范与行为约束。</p>
     *
     * @param agent  当前执行的智能体
     * @param locale 语言环境
     * @param sb     指令构建器
     */
    default void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) { }

    /**
     * [阶段：执行前] 动态准备智能体的任务提示词（Task Prompt）
     * <p>在 Agent 运行前执行，用于裁剪上下文、衔接前序 Agent 的备注（Memo）或注入黑板状态。</p>
     *
     * @param originalPrompt 原始提示词
     * @return 最终交付执行的提示词
     */
    default Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        return originalPrompt;
    }

    /**
     * [阶段：初始化] 注入主管（Supervisor）的静态系统提示词
     * <p>仅在团队初始化时触发一次，定义路由器的基本性格与准则。</p>
     */
    default void injectSupervisorInstruction(Locale locale, StringBuilder sb) { }

    /**
     * [阶段：决策前] 准备主管（Supervisor）决策时的动态补充指令
     * <p>在每一轮路由决策前触发，可动态注入当前执行进度统计、冲突信息等。</p>
     */
    default void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) { }

    /**
     * [阶段：决策拦截] 拦截主管（Supervisor）的执行逻辑
     * <p>用于实现确定性路由。若返回 true，将跳过 LLM 智能决策逻辑，由协议完全自主接管。 </p>
     */
    default boolean interceptSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        return false;
    }

    /**
     * [阶段：路由干预] 干预主管（Supervisor）的路由决策结果
     * <p>在 LLM 给出决策后触发，用于修正幻觉、解析协议特定信号词（如工具调用标记）并强制转向。</p>
     *
     * @param decision LLM 给出的原始决策文本
     * @return 若返回 true，表示协议已完成路由接管，将忽略通用的 Agent 名称匹配。
     */
    default boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        return false;
    }

    /**
     * [阶段：路由转向] 路由目标确定后的回调
     * <p>当下一个目标（Agent 或 End）明确后触发，常用于跨节点的数据同步或审计日志记录。</p>
     * * @param nextAgent 下一个执行节点的标识
     */
    default void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) { }

    /**
     * [阶段：销毁期] 团队任务结束后的清理回调
     * <p>当任务到达终点（End）或发生异常中断时触发，用于归档执行轨迹或释放协议资源。</p>
     */
    default void onTeamFinished(FlowContext context, TeamTrace trace) { }
}