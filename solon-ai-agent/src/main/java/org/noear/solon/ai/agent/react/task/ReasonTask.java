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
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
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

        // [逻辑 1: 安全限流] 防止无限循环，达到最大步数则强制终止并返回错误提示
        if (trace.nextStep() > trace.getOptions().getMaxSteps()) {
            LOG.warn("ReActAgent [{}] reached max steps: {}", config.getName(), trace.getOptions().getMaxSteps());
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Agent error: Maximum iterations reached.");
            return;
        }

        // [逻辑 2: 提示词工程] 融合系统角色、执行计划、输出格式约束及协议指令
        String systemPrompt = config.getSystemPromptFor(trace, context);

        if (trace.getOptions().isPlanningMode() && trace.hasPlans()) {
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

        if (LOG.isTraceEnabled()) {
            LOG.trace("ReActAgent SystemPrompt rendered for trace [{}]: {}", trace.getAgentName(), systemPrompt);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(systemPrompt));
        messages.addAll(trace.getWorkingMemory().getMessages());

        // [逻辑 3: 模型交互] 执行物理请求并触发模型响应相关的拦截器
        ChatResponse response = callWithRetry(node, trace, messages);
        AssistantMessage responseMessage = response.getMessage();
        if(responseMessage == null){
            responseMessage = response.getAggregationMessage();
        }

        if (response.getUsage() != null) {
            trace.getMetrics().addUsage(response.getUsage());
        }

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onModelEnd(trace, response);
        }

        // 触发推理审计事件（传递原始消息对象）
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onReason(trace, responseMessage);
        }

        // 容错处理：模型响应内容及工具调用均为空时，引导其重新生成
        if (Assert.isEmpty(responseMessage.getContent()) && Assert.isEmpty(responseMessage.getToolCalls())) {
            trace.getWorkingMemory().addMessage(ChatMessage.ofUser("Your last response was empty. Please provide Action or Final Answer."));
            trace.setRoute(ReActAgent.ID_REASON);
            return;
        }

        // [逻辑 4: 路由分发 - 基于原生工具调用协议]
        if (Assert.isNotEmpty(responseMessage.getToolCalls())) {
            trace.getWorkingMemory().addMessage(responseMessage);
            trace.setRoute(ReActAgent.ID_ACTION);
            return;
        }

        // [逻辑 5: 路由判断 - 文本 ReAct 协议解析]
        final String rawContent = responseMessage.hasContent() ? responseMessage.getContent() : ""; // 原始（含 think）
        final String clearContent = responseMessage.hasContent() ? responseMessage.getResultContent() : ""; // 干净（无 think）


        // 进一步清洗协议头（如 Thought:{...}\nAction:），提取核心思维逻辑
        final String thoughtContent = extractThought(clearContent);

        trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant(rawContent));
        trace.setLastResult(clearContent);

        // 触发思考事件（仅在存在有效思考文本时通知）
        if(Assert.isNotEmpty(thoughtContent)) {
            for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
                item.target.onThought(trace, thoughtContent);
            }
        }

        // [逻辑 6: 决策流控]

        // 决策基准采用 clearContent，确保不受 <think> 标签内干扰词影响

        // 1. 优先判断任务是否结束（Finish）
        if (clearContent.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent));
            return;
        }

        // 2. 其次判断文本形式的工具执行意图（Action: { ... }）
        if (clearContent.contains("Action:")) {
            String actionPart = clearContent.substring(clearContent.indexOf("Action:"));
            if (actionPart.matches("(?s)Action:\\s*\\{.*")) {
                trace.setRoute(ReActAgent.ID_ACTION);
                return;
            }
        }

        // 3. 兜底逻辑：既无明确工具调用也无完成标识，视为直接回复 Final Answer
        trace.setRoute(Agent.ID_END);
        trace.setFinalAnswer(extractFinalAnswer(clearContent));
    }

    private ChatResponse callWithRetry(Node node, ReActTrace trace, List<ChatMessage> messages) {
        if(LOG.isTraceEnabled()){
            LOG.trace("ReActAgent [{}] calling model... messages: {}",
                    config.getName(),
                    ONode.serialize(messages, Feature.Write_PrettyFormat, Feature.Write_EnumUsingName));
        }

        ChatRequestDesc req = config.getChatModel()
                .prompt(messages)
                .options(o -> {
                    if(trace.getOptions().isFeedbackMode()) {
                        o.toolAdd(FeedbackTool.getTool(
                                trace.getOptions().getFeedbackDescription(trace),
                                trace.getOptions().getFeedbackReasonDescription(trace)));
                    }

                    o.toolAdd(trace.getOptions().getTools());
                    o.toolAdd(trace.getProtocolTools());

                    o.autoToolCall(false); // 强制由 Agent 框架接管工具链路管理
                    o.toolContextPut(trace.getOptions().getToolContext());

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
                if (trace.getOptions().getStreamSink() != null) {
                    return req.stream().doOnNext(resp->{
                        trace.getOptions().getStreamSink()
                                .next(new ReasonChunk(node, trace, resp));
                    }).blockLast();
                } else {
                    return req.call();
                }
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
     * 移除技术性标签（如 <think>）及协议引导词（如 Thought:），获取纯净思考主体
     */
    private String extractThought(String clearContent) {
        if (Utils.isEmpty(clearContent)) {
            return "";
        }

        String result;
        int labelIndex = clearContent.indexOf(THOUGHT_LABEL);
        if(labelIndex < 0){
            return "";
        }

        result = clearContent.substring(labelIndex + THOUGHT_LABEL.length()).trim();

        labelIndex = result.indexOf("\nAction:");
        if (labelIndex > -1) {
            result = result.substring(0, labelIndex).trim();
        }

        return result;
    }

    /**
     * 清理推理过程，从思考片段中提取最终业务答案
     */
    private String extractFinalAnswer(String clearContent) {
        if (Utils.isEmpty(clearContent)) {
            return "";
        }

        String answer = clearContent;
        String marker = config.getFinishMarker();

        int markerIndex = answer.indexOf(marker);
        if (markerIndex < 0) {
            /**
             * 示例："\n\nThought: 用户想要转账500元给老张，但是缺少必需的收款人银行卡号信息，需要向用户询问。\nAction: 我需要向用户询问老张的银行卡号，因为这是执行转账操作的必需参数。"
             * */
            marker = "Action:";
            markerIndex = answer.indexOf(marker);
        }

        if (markerIndex < 0) {
            return "";
        }

        answer = answer.substring(markerIndex + marker.length()).trim();
        return answer;
    }

    private static final String THOUGHT_LABEL = "Thought:";
}