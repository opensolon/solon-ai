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
 * ReAct 推理任务（Reasoning Task）
 * <p>
 * 核心职责：
 * 1. 组合系统提示词与历史记忆，向 LLM 发起推理请求。
 * 2. 识别模型意图：是需要执行工具（Action）还是已经得到最终答案（Final Answer）。
 * 3. 兼容混合模式：同时支持模型原生的 ToolCall 协议和文本格式的 ReAct 协议。
 * </p>
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

        // 获取当前 Agent 的追踪状态标识
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);

        // [逻辑 1：迭代安全限制]
        // 检查当前步数是否超过配置的最大步数，防止 LLM 在复杂问题或错误逻辑中陷入无限死循环
        if (trace.nextStep() > config.getMaxSteps()) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Agent error: Maximum iterations reached.");
            return;
        }

        // [逻辑 2：上下文初始化]
        // 首轮迭代时，将 Prompt 转化为 User Message 加入历史对话序列
        if (Assert.isEmpty(trace.getMessages())) {
            Prompt prompt = trace.getPrompt();
            trace.appendMessage(prompt);
        }

        // [逻辑 3：构建消息全景]
        // 拼接 System Prompt (ReAct 规范) 和 动态增长的历史对话 (Thought/Action/Observation)
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(config.getSystemPrompt(trace)));
        messages.addAll(trace.getMessages());

        // [逻辑 4：执行模型推理]
        ChatResponse response = callWithRetry(trace, messages);

        // 处理模型空回复异常，通过 User 提示引导模型修正或结束
        if (response.hasChoices() == false || (Assert.isEmpty(response.getContent()) && Assert.isEmpty(response.getMessage().getToolCalls()))) {
            trace.appendMessage(ChatMessage.ofUser("Your last response was empty. If you need more info, use a tool. Otherwise, provide Final Answer."));
            trace.setRoute(Agent.ID_REASON);
            return;
        }

        // [逻辑 5：分支决策 - 原生工具调用 (Native Tool Calls)]
        // 如果模型通过标准的 ToolCall 协议响应，直接转向 Action 节点执行
        if (Assert.isNotEmpty(response.getMessage().getToolCalls())) {
            trace.appendMessage(response.getMessage());
            trace.setRoute(Agent.ID_ACTION);
            return;
        }

        // [逻辑 6：分支决策 - 文本格式推理 (Text ReAct Mode)]
        String rawContent = response.hasContent() ? response.getContent() : "";
        String clearContent = response.hasContent() ? response.getResultContent() : "";

        // 防御性逻辑：物理截断。LLM 偶尔会“自问自答”伪造 Observation 标签。
        // 通过截断确保流程必须经过 ActionTask 节点注入真实观察结果。
        if (rawContent.contains("Observation:")) {
            rawContent = rawContent.split("Observation:")[0];
        }

        trace.appendMessage(ChatMessage.ofAssistant(rawContent));
        trace.setLastResponse(clearContent);

        // 触发拦截器：外部可观察到的“思考过程”
        if (config.getInterceptor() != null) {
            config.getInterceptor().onThought(trace, clearContent);
        }

        // [逻辑 7：路由派发]
        if (rawContent.contains(config.getFinishMarker())) {
            // 匹配到结束标识，提取答案并标记流程结束
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        } else if (rawContent.contains("Action:")) {
            // 匹配到 Action 指令，转向 Action 节点执行工具
            trace.setRoute(Agent.ID_ACTION);
        } else {
            // 兜底策略：模型未能按规范输出时，尝试提取当前内容作为最终答案
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        }
    }

    /**
     * 重试调用封装
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

                            // 强制关闭模型端的自动工具执行，由 ReActActionTask 统一管控
                            o.autoToolCall(false);

                            if (!config.getTools().isEmpty()) {
                                o.toolsAdd(config.getTools());
                                // 注入停止序列，防止模型在推理阶段直接伪造外部观察结果
                                o.optionAdd("stop", "Observation:");
                            }
                        }).call();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    LOG.error("ReActAgent [{}] reason failed after {} retries", config.getName(), maxRetries, e);
                    throw new RuntimeException("Failed after " + maxRetries + " retries", e);
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("ReActAgent [{}] reason call failed (retry: {}): {}", config.getName(), i, e.getMessage());
                }

                try {
                    // 退避重试
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
     * 解析并清理输出内容，返回纯净的 Final Answer
     */
    private String extractFinalAnswer(String content) {
        if (content == null) return "";

        // 移除标识符之前的所有推理废话
        if (content.contains(config.getFinishMarker())) {
            content = content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length());
        }

        return content
                .replaceAll("(?s)<think>.*?</think>", "") // 兼容 DeepSeek 等模型的 <think> 标签清理
                .replaceAll("(?m)^(Thought|Action|Observation):\\s*", "") // 移除 ReAct 逻辑标签头
                .trim();
    }
}