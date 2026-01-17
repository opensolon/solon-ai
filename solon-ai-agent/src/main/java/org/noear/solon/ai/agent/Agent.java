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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
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
 * <p>定义 AI 智能体的行为契约。作为 {@link NamedTaskComponent} 接入 Solon Flow，实现分布式协作。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface Agent extends AgentHandler, NamedTaskComponent {
    static final Logger LOG = LoggerFactory.getLogger(Agent.class);

    /**
     * 智能体名称（唯一标识）
     */
    String name();

    /**
     * 智能体职责描述（用于语义路由与任务分发参考）
     */
    String description();

    /**
     * 智能体档案（能力画像与交互契约）
     */
    default AgentProfile profile() {
        return null;
    }

    /**
     * 生成动态职责描述（支持模板渲染）
     */
    default String descriptionFor(FlowContext context) {
        if (context == null) {
            return description();
        }

        return SnelUtil.render(description(), context.model());
    }

    /**
     * 响应式任务执行（继续上次的任务）
     *
     * @param session 会话上下文
     */
    default AssistantMessage call(AgentSession session) throws Throwable {
        return call(null, session);
    }

    /**
     * 指定指令的任务执行（开始新任务）
     *
     * @param prompt  显式指令
     * @param session 会话上下文
     */
    AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable;

    /**
     * Solon Flow 节点运行实现
     * <p>处理 Session 初始化、协议注入、推理执行及轨迹同步。</p>
     */
    @Override
    default void run(FlowContext context, Node node) throws Throwable {
        // 1. 获取或初始化会话
        AgentSession session = context.computeIfAbsent(KEY_SESSION, k -> new InMemoryAgentSession("tmp"));

        // 2. 处理团队协作轨迹与拦截
        String traceKey = context.getAs(KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;

        if (trace != null) {
            trace.setLastAgentName(this.name());
            for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
                if (item.target.shouldAgentContinue(trace, this) == false) {
                    trace.addRecord(ChatRole.ASSISTANT, name(),
                            "[Skipped] Cancelled by " + item.target.getClass().getSimpleName(), 0);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Agent [{}] execution skipped by interceptor: {}", name(), item.target.getClass().getSimpleName());
                    }
                    return;
                }
            }
        }

        // 3. 准备提示词并执行推理
        final Prompt effectivePrompt;
        if (trace != null) {
            effectivePrompt = trace.getProtocol().prepareAgentPrompt(trace, this, trace.getPrompt(), trace.getConfig().getLocale());
        } else {
            effectivePrompt = null;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Agent [{}] start calling...", name());
        }

        long start = System.currentTimeMillis();
        AssistantMessage msg = call(effectivePrompt, session);

        if (LOG.isTraceEnabled()) {
            LOG.trace("Agent [{}] return message: {}",
                    name(),
                    ONode.serialize(msg, Feature.Write_PrettyFormat, Feature.Write_EnumUsingName));
        }

        long duration = System.currentTimeMillis() - start;

        // 4. 同步执行轨迹与结果处理
        if (trace != null) {
            String rawContent = (msg.getContent() == null) ? "" : msg.getContent().trim();
            String finalResult = trace.getProtocol().resolveAgentOutput(trace, this, rawContent);

            if (finalResult == null || finalResult.isEmpty()) {
                finalResult = "Agent [" + name() + "] processed but returned no textual content.";
            }

            trace.addRecord(ChatRole.ASSISTANT, name(), finalResult, duration);

            // 执行后置回调
            trace.getProtocol().onAgentEnd(trace, this);
            trace.getOptions().getInterceptors().forEach(item -> item.target.onAgentEnd(trace, this));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Agent [{}] call completed in {}ms", name(), duration);
        }
    }

    // --- Context Keys ---
    static String KEY_CURRENT_UNIT_TRACE_KEY = "_current_unit_trace_key_";
    static String KEY_CURRENT_TEAM_TRACE_KEY = "_current_team_trace_key_";
    static String KEY_SESSION = "_SESSION_";
    static String KEY_PROTOCOL = "_PROTOCOL_";

    // --- Node IDs ---
    static String ID_START = "start";
    static String ID_END = "end";
}