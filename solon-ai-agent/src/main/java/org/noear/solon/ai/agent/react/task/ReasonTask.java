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
import org.noear.solon.ai.agent.react.ReActConfig;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.util.TmplUtil;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.expression.snel.SnEL;
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
 * 3. 拦截与监控：在模型交互前后提供拦截点，用于死循环检测、安全审计或 Token 统计。
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReasonTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ReasonTask.class);

    private final ReActConfig config;
    private final ReActAgent agent;

    public ReasonTask(ReActConfig config, ReActAgent agent) {
        this.config = config;
        this.agent = agent;
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

        // 获取当前 Agent 的状态追踪对象
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);

        // [逻辑 1：步数安全限制]
        // 检查迭代深度，防止模型在复杂任务中陷入无限逻辑死循环
        if (trace.nextStep() > config.getMaxSteps()) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Agent error: Maximum iterations reached.");
            return;
        }

        // [逻辑 2：上下文构建]
        // 注入 ReAct 规范提示词、协议指令及动态历史记录（Thought/Action/Observation）
        String systemPrompt = config.getPromptProvider().getSystemPrompt(trace);
        systemPrompt = TmplUtil.render(systemPrompt, context);

        if (trace.getProtocol() != null) {
            StringBuilder systemPromptBuilder = new StringBuilder(systemPrompt);
            trace.getProtocol().injectAgentInstruction(agent, config.getPromptProvider().getLocale(), systemPromptBuilder);
            systemPrompt = systemPromptBuilder.toString();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(systemPrompt));
        messages.addAll(trace.getMessages());

        // [逻辑 3：模型推理与生命周期拦截]
        // callWithRetry 负责物理层的网络调用与重试
        ChatResponse response = callWithRetry(trace, messages);

        // 触发模型响应拦截：常用于死循环检测（onModelEnd 抛出异常可提前终止推理流）
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            item.target.onModelEnd(trace, response);
        }

        // 防御性处理：检查模型是否返回了有效负载
        if (response.hasChoices() == false || (Assert.isEmpty(response.getContent()) && Assert.isEmpty(response.getMessage().getToolCalls()))) {
            trace.appendMessage(ChatMessage.ofUser("Your last response was empty. Please provide Action or Final Answer."));
            trace.setRoute(Agent.ID_REASON);
            return;
        }

        // [逻辑 4：路由分发 - 原生工具调用模式]
        // 模型支持 ToolCall 协议时，直接进入 ActionTask
        if (Assert.isNotEmpty(response.getMessage().getToolCalls())) {
            trace.appendMessage(response.getMessage());
            trace.setRoute(Agent.ID_ACTION);
            return;
        }

        // [逻辑 5：路由分发 - 文本 ReAct 模式解析]
        String rawContent = response.hasContent() ? response.getContent() : "";
        String clearContent = response.hasContent() ? response.getResultContent() : "";

        // 物理截断防御：防止模型伪造后续的 Observation 内容，确保观察结果由 ActionTask 生成
        if (rawContent.contains("Observation:")) {
            rawContent = rawContent.split("Observation:")[0];
        }

        trace.appendMessage(ChatMessage.ofAssistant(rawContent));
        trace.setLastAnswer(clearContent);

        // 触发思考拦截：通知外部模型当前的推理进展
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            item.target.onThought(trace, clearContent);
        }

        // [逻辑 6：决策路由]
        if (rawContent.contains(config.getFinishMarker())) {
            // 场景 A: 模型输出结束标记，进入结束节点并提取最终答案
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        } else if (rawContent.contains("Action:")) {
            // 场景 B: 模型输出 Action 指令，进入工具执行节点
            trace.setRoute(Agent.ID_ACTION);
        } else {
            // 场景 C: 兜底逻辑，若模型未按规范输出，则视当前内容为答案并结束
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
        }
    }

    /**
     * 带重试机制的模型调用封装
     */
    private ChatResponse callWithRetry(ReActTrace trace, List<ChatMessage> messages) {
        // 构建请求描述对象
        ChatRequestDesc req = config.getChatModel()
                .prompt(messages)
                .options(o -> {
                    // 1. 注入内置工具与协议扩展工具
                    if (!config.getTools().isEmpty()) {
                        o.toolsAdd(config.getTools());
                        o.optionPut("stop", Utils.asList("Observation:")); // 强制截断，保证 ReAct 闭环
                    }

                    if (!trace.getProtocolTools().isEmpty()) {
                        o.toolsAdd(trace.getProtocolTools());
                    }

                    // 2. 禁用模型端的自动工具执行，由 Agent 框架统一管控调度
                    o.autoToolCall(false);

                    // 3. 同步业务层拦截器到 Chat 层
                    for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
                        o.interceptorAdd(item.target);
                    }

                    if (config.getChatOptions() != null) {
                        config.getChatOptions().accept(o);
                    }
                });

        // 触发请求发起拦截：可在此阶段进行 Token 预警或动态修改请求参数
        for (RankEntity<ReActInterceptor> item : config.getInterceptorList()) {
            item.target.onModelStart(trace, req);
        }

        // 网络层重试循环
        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return req.call();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    LOG.error("ReActAgent [{}] reason failed after {} retries", config.getName(), maxRetries, e);
                    throw new RuntimeException("Reasoning failed after " + maxRetries + " retries", e);
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("ReActAgent [{}] call failed, retrying({}): {}", config.getName(), i + 1, e.getMessage());
                }

                try {
                    // 指数退避重试
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
     * 清理并提取纯净的 Final Answer
     * 移除思考标签（<think>）、逻辑标签（Thought/Action）及结束标识符
     */
    private String extractFinalAnswer(String content) {
        if (content == null) return "";

        // 仅保留 FinishMarker 之后的内容（如果存在）
        if (content.contains(config.getFinishMarker())) {
            content = content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length());
        }

        return content
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?m)^(Thought|Action|Observation):\\s*", "")
                .trim();
    }
}