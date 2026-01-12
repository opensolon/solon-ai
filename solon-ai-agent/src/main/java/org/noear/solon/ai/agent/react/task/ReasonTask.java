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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 推理任务 (Reasoning)
 * <p>核心职责：组装上下文发起请求，解析模型意图（Action/Final Answer），并执行路由分发。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReasonTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ReasonTask.class);

    private final ReActAgentConfig config;
    private final ReActAgent agent;

    public ReasonTask(ReActAgentConfig config, ReActAgent agent) {
        this.config = config;
        this.agent = agent;
    }

    @Override
    public String name() {
        return Agent.ID_REASON;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] reasoning... Step: {}/{}",
                    config.getName(), trace.getStepCount() + 1, trace.getOptions().getMaxSteps());
        }

        // [逻辑 1: 安全限流] 防止死循环，达到最大步数则强制终止
        if (trace.nextStep() > trace.getOptions().getMaxSteps()) {
            LOG.warn("ReActAgent [{}] reached max steps: {}", config.getName(), trace.getOptions().getMaxSteps());
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Agent error: Maximum iterations reached.");
            return;
        }

        // [逻辑 2: 系统提示词构建] 融合业务角色、ReAct协议与输出格式约束
        String systemPrompt = config.getSystemPromptFor(trace, context);

        if (Assert.isNotEmpty(trace.getConfig().getOutputSchema())) {
            systemPrompt += "\n\n[IMPORTANT: OUTPUT FORMAT REQUIREMENT]\n" +
                    "Please provide the Final Answer strictly following this schema:\n" +
                    trace.getConfig().getOutputSchema();
        }

        if (trace.getProtocol() != null) {
            StringBuilder sb = new StringBuilder(systemPrompt);
            trace.getProtocol().injectAgentInstruction(agent, config.getLocale(), sb);
            systemPrompt = sb.toString();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(systemPrompt));
        messages.addAll(trace.getMessages());

        // [逻辑 3: 模型交互] 执行物理请求与拦截器生命周期
        ChatResponse response = callWithRetry(trace, messages);

        if(response.getUsage() != null) {
            trace.getMetrics().addTokenUsage(response.getUsage().totalTokens());
        }

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onModelEnd(trace, response);
        }

        // 容错处理：模型响应为空时引导其重试
        if (response.hasChoices() == false || (Assert.isEmpty(response.getContent()) && Assert.isEmpty(response.getMessage().getToolCalls()))) {
            trace.appendMessage(ChatMessage.ofUser("Your last response was empty. Please provide Action or Final Answer."));
            trace.setRoute(Agent.ID_REASON);
            return;
        }

        // [逻辑 4: 路由判断 - 原生工具调用协议]
        if (Assert.isNotEmpty(response.getMessage().getToolCalls())) {
            trace.appendMessage(response.getMessage());
            trace.setRoute(Agent.ID_ACTION);
            return;
        }

        // [逻辑 5: 路由判断 - 文本 ReAct 协议解析]
        String rawContent = response.hasContent() ? response.getContent() : "";
        String clearContent = response.hasContent() ? response.getResultContent() : "";

        // 截断防御：防止模型“分身”替系统回复 Observation 内容
        if (rawContent.contains("Observation:")) {
            rawContent = rawContent.split("Observation:")[0];
        }

        trace.appendMessage(ChatMessage.ofAssistant(rawContent));
        trace.setLastAnswer(clearContent);

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onThought(trace, clearContent);
        }

        // [逻辑 6: 决策分流]
        if (rawContent.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        } else if (rawContent.contains("Action:")) {
            trace.setRoute(Agent.ID_ACTION);
        } else {
            // 兜底：未匹配到协议标识则默认视为最终答案
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        }
    }

    private ChatResponse callWithRetry(ReActTrace trace, List<ChatMessage> messages) {
        ChatRequestDesc req = config.getChatModel()
                .prompt(messages)
                .options(o -> {
                    if (config.getTools().size() > 0) {
                        o.toolsAdd(config.getTools());
                        o.optionPut("stop", Utils.asList("Observation:")); // 物理截断保证流程控制权
                    }

                    if(trace.getConfig().getOutputSchema() != null){
                        o.optionPut("response_format", Utils.asMap("type", "json_object"));
                    }

                    if (!trace.getProtocolTools().isEmpty()) {
                        o.toolsAdd(trace.getProtocolTools());
                    }

                    o.autoToolCall(false); // 强制由 Agent 框架管理工具链路

                    for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
                        o.interceptorAdd(item.target);
                    }

                    if (config.getChatOptions() != null) {
                        config.getChatOptions().accept(o);
                    }
                });

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onModelStart(trace, req);
        }

        int maxRetries = trace.getOptions().getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return req.call();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    LOG.error("ReActAgent [{}] failed after {} retries", config.getName(), maxRetries, e);
                    throw new RuntimeException("Reasoning failed after max retries", e);
                }

                LOG.warn("ReActAgent [{}] retry {}/{} due to: {}", config.getName(), i + 1, maxRetries, e.getMessage());

                try {
                    Thread.sleep(trace.getOptions().getRetryDelayMs() * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Unreachable");
    }

    /**
     * 清理思考过程，提取最终业务答案
     */
    private String extractFinalAnswer(String content) {
        if (content == null) return "";

        if (content.contains(config.getFinishMarker())) {
            content = content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length());
        }

        return content
                .replaceAll("(?s)<think>.*?</think>", "") // 移除 DeepSeek 等模型的内省标签
                .replaceAll("(?m)^(Thought|Action|Observation):\\s*", "")
                .trim();
    }
}