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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActConfig;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 推理任务
 * 优化点：支持原生 ToolCall + 文本 ReAct 混合模式
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReasonTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ReasonTask.class);

    private final ReActConfig config;

    public ReasonTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return Agent.ID_REASON;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] reason starting...", config.getName());
        }

        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);

        // 1. 迭代限制检查：防止 LLM 陷入无限逻辑循环
        if (trace.nextStep() > config.getMaxSteps()) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Agent error: Maximum iterations reached.");
            return;
        }

        // 2. 初始化对话：首轮将 prompt 转为 User Message
        if (Assert.isEmpty(trace.getMessages())) {
            Prompt prompt = trace.getPrompt();
            trace.appendMessage(prompt);
        }

        // 3. 构建全量消息（System + History）
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(config.getSystemPrompt(trace)));
        messages.addAll(trace.getMessages());

        // 4. 发起请求并配置 stop 序列（防止模型代写 Observation）
        ChatResponse response = callWithRetry(trace, messages);

        // --- 处理模型空回复 (防止流程卡死) ---
        if (response.hasChoices() == false || (Assert.isEmpty(response.getContent()) && Assert.isEmpty(response.getMessage().getToolCalls()))) {
            trace.appendMessage(ChatMessage.ofUser("Your last response was empty. If you need more info, use a tool. Otherwise, provide Final Answer."));
            trace.setRoute(Agent.ID_REASON);
            return;
        }

        // --- 处理 Native Tool Calls ---
        if (Assert.isNotEmpty(response.getMessage().getToolCalls())) {
            trace.appendMessage(response.getMessage());
            trace.setRoute(Agent.ID_ACTION);
            return;
        }

        // --- 处理文本 ReAct 模式 ---
        String rawContent = response.hasContent() ? response.getContent() : "";
        String clearContent = response.hasContent() ? response.getResultContent() : "";

        // --- 物理截断模型幻觉 (防止模型伪造 Observation) ---
        if (rawContent.contains("Observation:")) {
            rawContent = rawContent.split("Observation:")[0];
        }

        trace.appendMessage(ChatMessage.ofAssistant(rawContent));
        trace.setLastResponse(clearContent);

        if (config.getInterceptor() != null) {
            config.getInterceptor().onThought(trace, clearContent);
        }

        //决策路由
        if (rawContent.contains(config.getFinishMarker())) {
            // 结束
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        } else if (rawContent.contains("Action:")) {
            // 动作
            trace.setRoute(Agent.ID_ACTION);
        } else {
            // 兜底（结束）
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        }
    }

    /**
     * 简单的重试调用
     */
    private ChatResponse callWithRetry(ReActTrace trace, List<ChatMessage> messages) {
        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return config.getChatModel()
                        .prompt(messages)
                        .options(o -> {
                            if (config.getReasonOptions() != null) {
                                config.getReasonOptions().accept(o);
                            }

                            //这个要放在自定义之后
                            o.autoToolCall(false);

                            if (!config.getTools().isEmpty()) {
                                o.toolsAdd(config.getTools());
                                o.optionAdd("stop", "Observation:");
                            }
                        }).call();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    throw new RuntimeException("Failed after " + maxRetries + " retries", e);
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("ReActAgent [{}] reason call failed (retry: {}): {}", config.getName(), i, e.getMessage());
                }

                try {
                    Thread.sleep(config.getRetryDelayMs() * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
        throw new RuntimeException("Should not reach here");
    }

    /**
     * 解析并清理最终回复内容
     */
    private String extractFinalAnswer(String content) {
        if (content == null) return "";

        if (content.contains(config.getFinishMarker())) {
            content = content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length());
        }

        return content.replaceAll("(?s)<think>.*?</think>", "") // 清理思考过程
                .replaceAll("(?m)^(Thought|Action|Observation):\\s*", "") // 清理所有 ReAct 标签
                .trim();
    }
}