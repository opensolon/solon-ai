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
import org.noear.solon.ai.chat.ChatRole;
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
 * <p>定义了 AI 智能体的行为契约，集成了标识描述、推理评估与任务执行能力。</p>
 * <p>通过继承 {@link NamedTaskComponent}，使其可作为工作流节点接入 Solon Flow 编排网络。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface Agent extends AgentHandler, NamedTaskComponent {
    static final Logger LOG = LoggerFactory.getLogger(Agent.class);

    /**
     * 获取智能体名称（全局唯一标识）
     */
    String name();

    /**
     * 获取智能体职责描述（用于语义路由与 Prompt 角色设定）
     */
    String description();

    /**
     * 获取智能体档案（包含技能、约束与模态契约）
     */
    default AgentProfile profile() {
        return null;
    }

    /**
     * 为当前上下文生成动态职责描述（支持占位符渲染）
     */
    default String descriptionFor(FlowContext context) {
        if (context == null) {
            return description();
        }

        return SnelUtil.render(description(), context.model());
    }

    /**
     * 响应式任务执行
     */
    default AssistantMessage call(AgentSession session) throws Throwable {
        return call(null, session);
    }

    /**
     * 指定指令的任务执行
     *
     * @param prompt  显式指令（若为 null 则由实现类自行构建）
     * @param session 会话上下文（持有记忆足迹）
     */
    AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable;

    /**
     * 工作流执行入口（实现 Solon Flow 节点的自动化协作逻辑）
     */
    @Override
    default void run(FlowContext context, Node node) throws Throwable {
        // 1. 初始化会话
        AgentSession session = context.computeIfAbsent(KEY_SESSION, k -> new InMemoryAgentSession("tmp"));

        // 2. 获取团队协作轨迹（Trace）
        String traceKey = context.getAs(KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;

        if (trace != null) {
            trace.setLastAgentName(this.name());
            // 拦截器校验
            for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptorList()) {
                if (item.target.shouldAgentContinue(trace, this) == false) {
                    trace.addStep(ChatRole.ASSISTANT, name(),
                            "[Skipped] Execution cancelled by " + item.target.getClass().getSimpleName(),
                            0);

                    if (LOG.isInfoEnabled()) {
                        LOG.info("Agent [{}] skipped by interceptor: {}", name(), item.target.getClass().getName());
                    }
                    return;
                }
            }
        }

        // 3. 构建执行指令（基于协作协议）
        Prompt effectivePrompt = null;
        if (trace != null) {
            effectivePrompt = trace.getProtocol().prepareAgentPrompt(
                    trace, this, trace.getPrompt(),
                    trace.getConfig().getLocale());
        }

        // 4. 执行推理
        if (LOG.isDebugEnabled()) {
            LOG.debug("Agent [{}] start calling...", name());
        }

        long start = System.currentTimeMillis();
        AssistantMessage msg = call(effectivePrompt, session);
        long duration = System.currentTimeMillis() - start;

        // 5. 结果处理与足迹同步
        if (trace != null) {
            String rawContent = (msg.getContent() == null) ? "" : msg.getContent().trim();
            // 结果解析转换
            String finalResult = trace.getProtocol().resolveAgentOutput(trace, this, rawContent);

            if (finalResult == null || finalResult.isEmpty()) {
                finalResult = "Agent [" + name() + "] returned no textual content.";
            }

            // 存入轨迹并回调
            trace.addStep(ChatRole.ASSISTANT, name(), finalResult, duration);
            trace.getProtocol().onAgentEnd(trace, this);
            trace.getOptions().getInterceptorList().forEach(item -> item.target.onAgentEnd(trace, this));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Agent [{}] execution completed in {}ms", name(), duration);
        }
    }

    // --- 标准字典 Key ---
    static String KEY_CURRENT_TRACE_KEY = "_current_trace_key_";
    static String KEY_SESSION = "_SESSION_";
    static String KEY_PROTOCOL = "_PROTOCOL_";

    // --- 标准节点 ID ---
    static String ID_START = "start";
    static String ID_END = "end";
    static String ID_REASON = "reason";
    static String ID_ACTION = "action";
    static String ID_SUPERVISOR = "supervisor";
}