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
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.util.FeedbackTool;
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
import java.util.regex.Pattern;

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

        if(Agent.ID_END.equals(trace.getRoute())){
            //有可能在 action 的拦截里，要求终止
            return;
        }

        if (LOG.isDebugEnabled()) {
            if (trace.getOptions().isPlanningMode()) {
                String planDesc = "";
                if (trace.hasPlans() && trace.getPlanIndex() < trace.getPlans().size()) {
                    planDesc = " | Plan[" + (trace.getPlanIndex() + 1) + "]: " + trace.getPlans().get(trace.getPlanIndex());
                }
                LOG.debug("ReActAgent [{}] reasoning... Step: {}/{}{}",
                        config.getName(), trace.getStepCount() + 1, trace.getOptions().getMaxSteps(), planDesc);
            } else {
                LOG.debug("ReActAgent [{}] reasoning... Step: {}/{}",
                        config.getName(), trace.getStepCount() + 1, trace.getOptions().getMaxSteps());
            }
        }

        // [逻辑 1: 安全限流 & 互动续航]
        int currentStep = trace.nextStep(); // 这里会自增步数
        int maxSteps = trace.getOptions().getMaxSteps();
        int maxStepsLimit = trace.getOptions().getMaxStepsLimit();

        // 1.1 绝对硬限熔断：超过 100 步（maxStepsLimit），无论如何都必须死掉
        if (currentStep > maxStepsLimit) {
            LOG.error("ReActAgent [{}] hard limit hit: {}", config.getName(), maxStepsLimit);
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("检测到异常推理循环，已达到硬性步数上限 (" + maxStepsLimit + ")。");
            return;
        }

        // 1.2 软限熔断：超过了当前设定的 maxSteps (比如 8 步)
        if (currentStep > maxSteps) {
            // 真正超过最大步数，彻底终止
            LOG.warn("ReActAgent [{}] reached max steps: {}", config.getName(), maxSteps);
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Agent error: Maximum steps reached (" + maxSteps + ").");
            return;
        }

        // 1.3 临界预警：刚好到 80% 或最后 1 步时，且开启了反馈模式
        if(trace.getOptions().isMaxStepsExtensible()) {
            int thresholdStep = Math.max(maxSteps - 1, (int) (maxSteps * 0.8));

            if (currentStep >= thresholdStep) {
                // 检查用户是否已经通过 HITL 决策过“继续”
                HITLDecision decision = trace.getContext().getAs(HITL.DECISION_PREFIX + FeedbackTool.TOOL_NAME);

                if (decision == null) {
                    // 核心创新：伪造一个 Feedback 请求，挂起任务
                    String warningMsg = String.format("Agent 已执行 %d 步（上限 %d 步），任务尚未完成。是否允许继续执行？",
                            currentStep, maxSteps);

                    // 1. 记录挂起任务
                    Map<String, Object> args = new HashMap<>();
                    args.put("reason", warningMsg);
                    args.put("type", "step_limit_warning");

                    trace.getContext().put(HITL.LAST_INTERVENED, new HITLTask(FeedbackTool.TOOL_NAME, args, warningMsg));

                    // 2. 设为挂起状态
                    trace.pending(warningMsg);
                    trace.setFinalAnswer(warningMsg); // 让前端能展示这个询问提示

                    LOG.info("ReActAgent [{}] paused at threshold step: {}/{}", config.getName(), currentStep, maxSteps);
                    return;
                } else {
                    // 如果用户已经决策了（approve），则重置步数或扩大步数，让 Agent 继续跑
                    if (decision.isApproved()) {
                        // 方案：给 Agent 续命，增加步数上限（或者简单地将 stepCount 减去一部分）
                        int nextMaxSteps = Math.min(maxSteps + 10, maxStepsLimit);
                        trace.getOptions().setMaxSteps(nextMaxSteps);

                        LOG.info("ReActAgent [{}] approved to continue. New max steps: {}",
                                config.getName(), trace.getOptions().getMaxSteps());

                        // 清理决策状态，防止死循环
                        trace.getContext().remove(HITL.DECISION_PREFIX + FeedbackTool.TOOL_NAME);
                        trace.getContext().remove(HITL.LAST_INTERVENED);
                    }
                }
            }
        }

        // [逻辑 2: 提示词工程] 融合系统角色、执行计划、输出格式约束及协议指令
        String systemPrompt = config.getSystemPromptFor(trace, context);

        if (trace.getOptions().isPlanningMode() && trace.hasPlans()) {
            StringBuilder sb = new StringBuilder("\n\n[执行计划进度]\n");
            List<String> plans = trace.getPlans();
            int currIdx = trace.getPlanIndex();
            int totalPlans = plans.size();

            for (int i = 0; i < totalPlans; i++) {
                String status = (i < currIdx) ? "[√] " : (i == currIdx ? "[●] " : "[ ] ");
                sb.append(i + 1).append(". ").append(status).append(plans.get(i)).append("\n");
            }

            if (currIdx >= totalPlans) {
                sb.append("\n**🎉 所有计划已全部完成！**\n")
                        .append("- 请根据上述已确认的所有信息，直接为用户提供最终的详细回答。");
            } else {
                sb.append("\n**计划进度同步协议：**\n");

                if (currIdx < totalPlans - 1) {
                    sb.append("- 当阶段任务 [").append(currIdx + 1).append("] 完成时，必须调用工具 `")
                            .append(PlanTool.TOOL_NAME).append("` 更新至索引 `").append(currIdx + 2).append("`。\n");
                } else {
                    sb.append("- 当前为最后一项任务。完成后，必须调用工具 `")
                            .append(PlanTool.TOOL_NAME).append("` 传入 `")
                            .append(totalPlans + 1).append("`（代表所有计划已圆满完成）。\n");
                }

                sb.append("- 只有当所有计划项都标记为 [√] 且收到成功反馈后，才允许输出最终答案并结束任务。\n");
            }

            systemPrompt += sb.toString();
        }

        if (trace.isPending()) {
            // 如果是从挂起状态恢复（例如 HITL 后继续）
            systemPrompt += "\n\n[Human-In-The-Loop Context]\n" +
                    "用户已对你的执行流程进行了审核并准许继续。请结合最新的 Observation 反馈调整你的下一步策略。";
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
        if(trace.isPending()){
            return;
        }

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

        if(trace.isPending()){
            return;
        }

        // 触发推理审计事件（传递原始消息对象）
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onReason(trace, responseMessage);
        }

        if(trace.isPending()){
            return;
        }

        // 容错处理：模型响应内容及工具调用均为空时，引导其重新生成
        if (Assert.isEmpty(responseMessage.getContent()) && Assert.isEmpty(responseMessage.getToolCalls())) {
            trace.getWorkingMemory().addMessage(ChatMessage.ofUser("Your last response was empty. Please provide Action or Final Answer."));
            trace.setRoute(ReActAgent.ID_REASON);
            return;
        }

        // [逻辑 4: 路由分发 - 基于原生工具调用协议]
        if (Assert.isNotEmpty(responseMessage.getToolCalls())) {
            trace.setLastReasonMessage(responseMessage);
            trace.setRoute(ReActAgent.ID_ACTION);
            return;
        }

        // [逻辑 5: 路由判断 - 文本 ReAct 协议解析]
        final String clearContent = responseMessage.hasContent() ? responseMessage.getResultContent() : ""; // 干净（无 think）


        // 进一步清洗协议头（如 Thought:{...}\nAction:），提取核心思维逻辑
        final String thoughtContent = extractThought(trace, clearContent);

        trace.setLastReasonMessage(responseMessage);

        // 触发思考事件（仅在存在有效思考文本时通知）
        if(Assert.isNotEmpty(thoughtContent)) {
            for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
                item.target.onThought(trace, thoughtContent);
            }
        }

        if(trace.isPending()){
            return;
        }

        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            if (Assert.isNotEmpty(clearContent)) {
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer(clearContent); // 直接取干净的正文
                return;
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
            if (actionPart.length() > 7) {
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
                    if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
                        o.toolAdd(trace.getOptions().getTools());
                        o.toolAdd(trace.getProtocolTools());
                    }

                    o.autoToolCall(false); // 强制由 Agent 框架接管工具链路管理
                    o.toolContextPut(trace.getOptions().getToolContext());

                    trace.getOptions().getInterceptors().forEach(item -> o.interceptorAdd(item.index, item.target));

                    if (trace.getOptions().getOutputSchema() != null) {
                        o.optionSet("response_format", Utils.asMap("type", "json_object"));
                    }

                    o.optionSet(trace.getOptions().getModelOptions().options());
                });

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onModelStart(trace, req);
        }

        if(trace.isPending()){
            return null;
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
    private String extractThought(ReActTrace trace, String clearContent) {
        if (Utils.isEmpty(clearContent)) {
            return "";
        }

        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            return clearContent;
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