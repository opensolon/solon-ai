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
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * 团队协作协议 (Team Collaboration Protocol)
 *
 * <p>核心职责：
 * <ul>
 * <li><b>拓扑构建：</b>定义 Agent 间的连接逻辑（如星型、环型、线型）</li>
 * <li><b>指令注入：</b>定制不同协作模式下的行为约束（如“仅执行”、“需转交”）</li>
 * <li><b>提示词干预：</b>动态衔接上下文、注入前序任务 Memo 或黑板状态</li>
 * <li><b>路由治理：</b>拦截、解析或干预 Supervisor 的决策流向</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamProtocol extends NonSerializable {

    /**
     * 获取协议唯一标识（如 "SEQUENTIAL", "SWARM", "A2A"）
     */
    String name();

    // --- 阶段一：构建期 (Build Time) ---

    /**
     * 构建团队协作图的拓扑结构
     * <p>定义节点间的静态链路。例如：Sequential 会构建 A -> B -> C 的有向链条。</p>
     *
     * @param spec 流程图规范定义器
     */
    void buildGraph(GraphSpec spec);

    // --- 阶段二：Agent 执行生命周期 (Agent Runtime) ---

    /**
     * 为当前运行的 Agent 注入协议专属工具
     * <p>常用于协作控制。例如在 A2A 协议中注入 {@code __transfer_to__} 工具。</p>
     *
     * @param agent 当前执行的智能体
     * @param trace 运行轨迹上下文
     */
    default void injectAgentTools(Agent agent, ReActTrace trace) { }

    /**
     * 为当前执行的 Agent 注入系统行为指令
     * <p>定义该 Agent 在当前协议下的角色规范。例如：禁止自行回答，必须寻求协助等。</p>
     *
     * @param agent  当前执行的智能体
     * @param locale 语言环境
     * @param sb     指令构建器（StringBuilder）
     */
    default void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) { }

    /**
     * 动态准备当前 Agent 的任务提示词 (Prompt Engineering)
     * <p>在请求模型前触发。用于衔接前序 Agent 留下的备注（Memo）或注入共享状态。</p>
     *
     * @param trace          协作轨迹
     * @param agent          当前智能体
     * @param originalPrompt 原始提示词
     * @return 最终提交给模型的提示词
     */
    default Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        return originalPrompt;
    }

    /**
     * 解析并转换当前 Agent 的输出内容
     * <p>在 Agent 执行完毕后，记录轨迹前触发。用于实现不同 Agent 间的内容适配或结果提取。</p>
     *
     * @param trace   协作轨迹
     * @param agent   当前执行的智能体
     * @param content 原始输出内容
     * @return 最终记录到轨迹并传递给下一阶段的内容
     */
    default String resolveAgentOutput(TeamTrace trace, Agent agent, String content) {
        return content;
    }

    /**
     * Agent 节点执行结束后的回调
     */
    default void onAgentEnd(TeamTrace trace, Agent agent) { }

    // --- 阶段三：主管决策治理 (Supervisor Governance) ---

    /**
     * 注入主管 (Supervisor) 的静态系统指令
     * <p>仅在初始化时触发，定义调度员的全局性格与分发准则。</p>
     */
    default void injectSupervisorInstruction(Locale locale, StringBuilder sb) { }

    /**
     * 准备主管决策时的动态补充指令
     * <p>在每轮决策前触发，用于注入实时的进度摘要或环境感知信息。</p>
     */
    default void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) { }

    /**
     * 决策执行拦截
     * <p>若返回 false，将跳过默认的 LLM 智能决策逻辑。常用于固定流向协议（如 Sequential）。</p>
     */
    default boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        return true;
    }

    /**
     * 解析路由目标 (Semantic Routing)
     * <p>将决策文本（或 ToolCall）转化为明确的节点 ID。优先级高于系统正则匹配。</p>
     *
     * @param decision 原始决策文本或信号词
     * @return 目标节点 ID 或 {@link Agent#ID_END}；返回 null 则由系统默认解析逻辑接管
     */
    default String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        return null;
    }

    /**
     * 路由决策预干预
     * <p>在路由目标确定前触发。若返回 false，协议可在此逻辑中通过 {@code trace.setRoute()} 自行改变方向。</p>
     */
    default boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        return true;
    }

    /**
     * 路由转向最终回调
     * <p>当下一个执行目标明确后触发。常用于跨节点的元数据同步或审计日志记录。</p>
     *
     * @param nextAgent 确定的下一个执行节点标识
     */
    default void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) { }

    // --- 阶段四：销毁与清理 (Destruction) ---

    /**
     * 团队协作任务完成后的终态清理
     * <p>当执行流到达 End 或发生异常中断时触发，用于清理上下文快照或释放临时资源。</p>
     */
    default void onTeamFinished(FlowContext context, TeamTrace trace) { }
}