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
package org.noear.solon.ai.agent;

import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能体核心接口
 *
 * <p>定义了 AI 智能体的行为契约，集成了标识、推理评估与任务执行能力。</p>
 * <p>该接口同时继承了 {@link NamedTaskComponent}，使其能够无缝接入 Solon Flow 工作流引擎，
 * 作为分布式协作网络中的一个功能节点（Worker）。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface Agent extends NamedTaskComponent {
    static final Logger LOG = LoggerFactory.getLogger(Agent.class);

    /**
     * 获取智能体名称（全局唯一标识）
     */
    String name();

    /**
     * 获取智能体职责描述
     * <p>描述该智能体擅长的领域或具备的能力，是 Supervisor 进行语义路由和任务分发的核心参考。</p>
     */
    String description();

    /**
     * 为当前上下文生成动态职责描述
     * <p>支持对 {@link #description()} 中的占位符（如 #{var}）进行渲染，实现动态角色设定。</p>
     */
    default String descriptionFor(FlowContext context){
        return SnelUtil.render(description(), context.model());
    }

    /**
     * 任务评估与能力竞标
     * <p>在合同网（Contract Net）等协作协议下，智能体通过此方法对特定任务进行自评，
     * 返回其解决该问题的匹配度、初步方案或预估代价。</p>
     *
     * @param session 当前交互会话
     * @param prompt  待评估的任务提示词
     * @return 评估结果或竞标说明（默认返回静态描述）
     */
    default String estimate(AgentSession session, Prompt prompt) {
        return descriptionFor(session.getSnapshot());
    }

    /**
     * 响应式任务执行（基于现有上下文）
     *
     * @param session 会话上下文（持有历史记忆）
     * @return AI 模型生成的响应消息
     */
    default AssistantMessage call(AgentSession session) throws Throwable {
        return call(null, session);
    }

    /**
     * 指定指令的任务执行
     *
     * @param prompt  显式指定的任务指令（优先级最高）
     * @param session 会话上下文
     * @return AI 模型生成的响应消息
     */
    AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable;

    /**
     * 工作流触发入口（Solon Flow 节点实现）
     * <p>该方法实现了智能体在团队协作流中的自动化运行逻辑：</p>
     * <ul>
     * <li>1. 自动初始化并维护 {@link AgentSession}。</li>
     * <li>2. 根据协作协议（Protocol）动态重构提示词，注入团队协作进度。</li>
     * <li>3. 执行 AI 推理并记录执行轨迹（Trace）及耗时。</li>
     * <li>4. 为后续路由决策更新状态（KEY_LAST_AGENT_NAME）。</li>
     * </ul>
     *
     * @param context 流上下文（包含全局 Trace 及配置）
     * @param node    工作流节点元数据
     */
    @Override
    default void run(FlowContext context, Node node) throws Throwable {
        // 记录当前执行者的标识，供路由器（Router）回溯
        context.put(KEY_LAST_AGENT_NAME, name());

        // 获取或创建会话
        AgentSession session = context.computeIfAbsent(KEY_SESSION, k -> new InMemoryAgentSession("tmp"));

        // 尝试获取团队协作轨迹
        String traceKey = context.getAs(KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;

        if(trace != null){
            for (RankEntity<TeamInterceptor> item : trace.getConfig().getInterceptorList()) {
                if (item.target.shouldAgentContinue(trace, this) == false) {
                    trace.addStep(name(),
                            "[Skipped] Agent execution was intercepted and cancelled by " + item.target.getClass().getSimpleName(),
                            0);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("TeamAgent agent [{}] execution skipped by interceptor", name());
                    }
                    return;
                }
            }
        }


        // 根据协作协议介入：注入前序工作进度、任务备注（Memo）等信息
        Prompt effectivePrompt = null;
        if (trace != null) {
            effectivePrompt = trace.getProtocol().prepareAgentPrompt(
                    trace, this, trace.getPrompt(),
                    trace.getConfig().getLocale());
        }

        // 调用推理引擎
        long start = System.currentTimeMillis();
        AssistantMessage msg = call(effectivePrompt, session);
        long duration = System.currentTimeMillis() - start;

        // 自动将执行轨迹同步到团队足迹中
        if (trace != null) {
            String result = (msg.getContent() == null) ? "" : msg.getContent().trim();
            if (result.isEmpty()) {
                result = "Agent [" + name() + "] processed but returned no textual content.";
            }
            trace.addStep(name(), result, duration);

            trace.getProtocol().onAgentEnd(trace, this);

            trace.getConfig().getInterceptorList().forEach(item -> item.target.onAgentEnd(trace, this));
        }
    }

    // --- 标准上下文字典 Key ---

    /** 当前活跃轨迹的 Key */
    static String KEY_CURRENT_TRACE_KEY = "_current_trace_key_";
    /** 最后执行的智能体名称 */
    static String KEY_LAST_AGENT_NAME = "_last_agent_name_";
    /** 会话对象存储 Key */
    static String KEY_SESSION = "SESSION";
    /** 协作协议存储 Key */
    static String KEY_PROTOCOL = "PROTOCOL";

    // --- 标准节点标识 ID ---

    static String ID_START = "start";
    static String ID_END = "end";
    static String ID_REASON = "reason";
    static String ID_ACTION = "action";
    static String ID_SYSTEM = "system";
    static String ID_SUPERVISOR = "supervisor";
    static String ID_BIDDING = "bidding";
}