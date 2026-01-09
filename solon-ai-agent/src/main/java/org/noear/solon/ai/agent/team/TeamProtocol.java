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
 * 团队协作协议接口
 *
 * <p>定义 Agent 团队的协作模式（如 SEQUENTIAL, SWARM, HIERARCHICAL 等）。</p>
 * <p>负责控制团队的拓扑结构构建、Agent 参数注入、提示词动态准备及执行路由的干预决策。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamProtocol extends NonSerializable {
    /**
     * 获取协议唯一标识
     */
    String name();

    /**
     * [生命周期：构建期] 构建团队协作图的拓扑结构
     * <p>用于定义节点间的逻辑连接（如 A -> B 或 A -> Supervisor）。</p>
     *
     * @param spec 图规格定义
     */
    void buildGraph(GraphSpec spec);

    /**
     * [生命周期：Agent 执行前] 注入智能体的运行选项
     * <p>常用于动态注入协作工具（如 TransferTool）、调整采样参数或设置停止符。</p>
     *
     * @param agent   目标智能体
     */
    default void injectAgentTools(Agent agent, ReActTrace trace) { }

    /**
     * [生命周期：Agent 执行前] 注入智能体的系统指令
     * <p>用于向 Agent 传达当前协议下的协作规范（如：如何移交任务）。</p>
     *
     * @param agent  目标智能体
     * @param locale 语言环境
     * @param sb     指令构建器
     */
    default void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) { }

    /**
     * [生命周期：Agent 执行前] 准备智能体的任务提示词
     * <p>用于根据协作历史（Trace）裁剪上下文、格式化历史记录或注入状态信息。</p>
     *
     * @return 最终交付给 Agent 执行的提示词
     */
    default Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        return originalPrompt;
    }

    /**
     * [生命周期：初始化] 注入主管（Supervisor）的静态系统提示词
     */
    default void injectSupervisorInstruction(Locale locale, StringBuilder sb) { }

    /**
     * [生命周期：决策前] 准备主管（Supervisor）运行时的动态补充指令
     * <p>例如：注入当前的黑板数据、各 Agent 的竞标书摘要或执行次数统计。</p>
     */
    default void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) { }

    /**
     * [生命周期：决策拦截] 拦截主管（Supervisor）的执行逻辑
     * <p>若返回 true，则表示协议已自主接管跳转逻辑（如顺序模式），跳过 LLM 智能路由决策。</p>
     */
    default boolean interceptSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        return false;
    }

    /**
     * [生命周期：路由干预] 在智能决策完成后，干预路由解析结果
     * <p>用于修正 LLM 的幻觉、解析协议特定的信号词或强制转向。</p>
     *
     * @param decision LLM 给出的原始决策文本
     * @return true 表示协议已接管路由，不再执行通用的名称匹配逻辑
     */
    default boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        return false;
    }

    /**
     * [生命周期：路由转向] 路由确定后的回调钩子
     * <p>当下一个执行目标（Agent 或 End）明确后触发，常用于状态同步或统计记录。</p>
     */
    default void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) { }

    /**
     * [生命周期：任务结束] 团队任务结束后的清理工作
     * <p>当流程到达 End 节点或异常中断时触发，用于释放资源或归档数据。</p>
     */
    default void onTeamFinished(FlowContext context, TeamTrace trace) { }
}