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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
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

import java.util.*;

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
        return ReActAgent.ID_REASON;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_UNIT_TRACE_KEY);
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

        if (trace.getOptions().isEnablePlanning() && trace.hasPlans()) {
            systemPrompt += "\n\n[执行计划]\n" + String.join("\n", trace.getPlans()) +
                    "\n请参考以上计划执行，当前已进行到第 " + (trace.getStepCount() + 1) + " 轮推理。";
        }

        if (Assert.isNotEmpty(trace.getOptions().getOutputSchema())) {
            systemPrompt += "\n\n[IMPORTANT: OUTPUT FORMAT REQUIREMENT]\n" +
                    "Please provide the Final Answer strictly following this schema:\n" +
                    trace.getOptions().getOutputSchema();
        }

        if (trace.getProtocol() != null) {
            StringBuilder sb = new StringBuilder(systemPrompt);
            trace.getProtocol().injectAgentInstruction(context, agent, config.getLocale(), sb);
            systemPrompt = sb.toString();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(systemPrompt));
        messages.addAll(trace.getMessages());

        // [逻辑 3: 模型交互] 执行物理请求与拦截器生命周期
        ChatResponse response = callWithRetry(trace, messages);

        if (response.getUsage() != null) {
            trace.getMetrics().addTokenUsage(response.getUsage().totalTokens());
        }

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onModelEnd(trace, response);
        }

        // 容错处理：模型响应为空时引导其重试
        if (response.hasChoices() == false || (Assert.isEmpty(response.getContent()) && Assert.isEmpty(response.getMessage().getToolCalls()))) {
            trace.appendMessage(ChatMessage.ofUser("Your last response was empty. Please provide Action or Final Answer."));
            trace.setRoute(ReActAgent.ID_REASON);
            return;
        }

        // [逻辑 4: 路由判断 - 原生工具调用协议]
        if (Assert.isNotEmpty(response.getMessage().getToolCalls())) {
            trace.appendMessage(response.getMessage());
            trace.setRoute(ReActAgent.ID_ACTION);
            return;
        }

        // [逻辑 5: 路由判断 - 文本 ReAct 协议解析]
        String rawContent = response.hasContent() ? response.getContent() : "";
        String clearContent = response.hasContent() ? response.getResultContent() : "";

        // 截断防御：防止模型“分身”替系统回复 Observation 内容
        int obsIndex = rawContent.indexOf("Observation:");
        if (obsIndex != -1) {
            rawContent = rawContent.substring(0, obsIndex).trim();
        }

        trace.appendMessage(ChatMessage.ofAssistant(rawContent));
        trace.setLastResult(clearContent);

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onThought(trace, clearContent);
        }

        // [逻辑 6: 决策分流]

        // 1. 优先判断是否完成（防止 Action 标识被误触发）
        if (rawContent.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
            return;
        }

        // 2. 其次判断文本形式的 Action（使用更严格的正则：Action: 后必须跟 {）
        if (rawContent.contains("Action:")) {
            String actionPart = rawContent.substring(rawContent.indexOf("Action:"));
            if (actionPart.matches("(?s)Action:\\s*\\{.*")) {
                trace.setRoute(ReActAgent.ID_ACTION);
                return;
            }
        }

        // 3. 兜底：既没有明确 Action，也没有明确 Finish，视为直接回答
        trace.setRoute(Agent.ID_END);
        trace.setFinalAnswer(extractFinalAnswer(clearContent));
    }

    private ChatResponse callWithRetry(ReActTrace trace, List<ChatMessage> messages) {
        if(LOG.isTraceEnabled()){
            LOG.trace("ReActAgent [{}] calling model... messages: {}",
                    config.getName(),
                    ONode.serialize(messages, Feature.Write_PrettyFormat, Feature.Write_EnumUsingName));
        }

        ChatRequestDesc req = config.getChatModel()
                .prompt(messages)
                .options(o -> {
                    o.toolAdd(trace.getOptions().getTools());
                    o.toolAdd(trace.getProtocolTools());

                    o.autoToolCall(false); // 强制由 Agent 框架管理工具链路
                    o.toolContextPut(trace.getOptions().getToolContext());

                    //trace.getOptions().getSkills().forEach(item -> o.skillAdd(item.index, item.target));
                    trace.getOptions().getInterceptors().forEach(item -> o.interceptorAdd(item.index, item.target));

                    if(trace.getOptions().getOutputSchema() != null){
                        o.optionSet("response_format", Utils.asMap("type", "json_object"));
                    }

                    o.optionSet(trace.getOptions().getModelOptions().options());
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