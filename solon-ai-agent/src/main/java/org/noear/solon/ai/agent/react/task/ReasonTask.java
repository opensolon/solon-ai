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
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.exception.LlmNoReturnException;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * ReAct 推理任务 (Reasoning)
 * <p>核心职责：组装上下文发起请求，解析模型意图（Action/Final Answer），并执行路由分发。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReasonTask {
    private static final Logger LOG = LoggerFactory.getLogger(ReasonTask.class);

    private final ReActAgentConfig config;
    private final ReActAgent agent;

    public ReasonTask(ReActAgentConfig config, ReActAgent agent) {
        this.config = config;
        this.agent = agent;
    }

    public String name() {
        return ReActAgent.ID_REASON;
    }

    public void run(ReActTrace trace, FlowContext context) throws Throwable {
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
                LOG.debug("ReActAgent [{}] reasoning... Turn: {}/{}{}",
                        config.getName(), trace.getTurnCount() + 1, trace.getOptions().getMaxTurns(), planDesc);
            } else {
                LOG.debug("ReActAgent [{}] reasoning... Turn: {}/{}",
                        config.getName(), trace.getTurnCount() + 1, trace.getOptions().getMaxTurns());
            }
        }

        // --- 优化点 1: 回合计数逻辑简化 ---
        int currentTurn = trace.nextTurn();
        int maxTurns = trace.getOptions().getMaxTurns();

        // --- 优化点 2: 统一流控逻辑，移除硬熔断 ---
        // 逻辑更加扁平化：要么进入 AutoRethink 机制，要么直接达到 maxTurns 熔断
        if (trace.getOptions().isAutoRethink()) {
            // [AutoRethink 模式]
            // 达到 80% 回合数时提前介入，留出 20% 的 buffer 让模型执行自审和策略调整
            int thresholdTurn = Math.max(maxTurns - 1, (int) (maxTurns * 0.8));

            if (currentTurn >= thresholdTurn) {
                // 自动扩展回合数上限（续航）
                int addTurns = Math.max(10, trace.getOptions().getInitialMaxTurns() / 2);
                trace.getOptions().addMaxTurns(addTurns);
                LOG.info("ReActAgent [{}] auto-rethink triggered. New maxTurns: {}", config.getName(), trace.getOptions().getMaxTurns());

                String rethinkPrompt = String.format(
                                "【系统指令：自我反思 (Self-Reflection)】\n" +
                                "当前任务已执行至第 %d 回合。为了确保任务准确高效完成，请立即启动自审程序：\n\n" +
                                "1. **核心目标检查**：重新审视用户最初提出的核心问题和当前要解决的任务，评估你当前的方向是否偏离了主线？\n" +
                                "2. **有效性评估**：检查历史 Observation。如果最近的尝试没有带来有效新线索，说明当前策略已失效，请必须更换思路或换个角度切入。\n" +
                                "3. **强制收敛**：若评估判定由于客观限制确实无法达成，请梳理已知线索，并在 Final Answer 中向用户复盘并申请协助。\n\n" +
                                "根据新策略决定下一步行动，或输出 Final Answer 结束任务",
                        currentTurn
                );

                trace.getWorkingMemory().addMessage(ChatMessage.ofUser(rethinkPrompt));
                LOG.info("ReActAgent [{}] auto-rethink triggered at turn {}", config.getName(), currentTurn);
            }
        } else {
            // [标准模式]
            // --- 优化点 3: 严格边界判定 ---
            if (currentTurn > maxTurns) {
                LOG.warn("ReActAgent [{}] reached max turns: {}", config.getName(), maxTurns);
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer("Agent error: Maximum turns reached (" + maxTurns + ").");
                return;
            }
        }

        // [逻辑 2: 提示词工程] 融合系统角色、执行计划、输出格式约束及协议指令
        StringBuilder systemPromptBuf = new StringBuilder();
        String baseSp = config.getSystemPromptFor(trace, context);
        if (baseSp != null) {
            systemPromptBuf.append(baseSp);
        }

        if (trace.getOptions().isPlanningMode() && trace.hasPlans()) {
            systemPromptBuf.append("\n\n[执行计划进度看板]\n");

            List<String> plans = trace.getPlans();
            int currIdx = trace.getPlanIndex();
            int total = plans.size();

            for (int i = 0; i < total; i++) {
                String status = (i < currIdx) ? "[√] " : (i == currIdx ? "[●] " : "[ ] ");
                systemPromptBuf.append(i + 1).append(". ").append(status).append(plans.get(i)).append("\n");
            }

            systemPromptBuf.append("\n**计划进度同步协议 (Plan Sync Protocol)：**\n");
            if (currIdx < total) {
                int currentStepNum = currIdx + 1;
                int nextStepNum = currIdx + 2;

                systemPromptBuf.append("- **当前状态**: 你正在执行步骤 [").append(currentStepNum).append("]。\n");
                systemPromptBuf.append("- **正常推进**: 步骤完成后，若结果符合预期，必须调用 `update_plan_progress` 并将 `next_plan_index` 设为 `").append(nextStepNum).append("` ");

                if (currIdx == total - 1) {
                    systemPromptBuf.append("(标志所有计划已达成)。\n");
                } else {
                    systemPromptBuf.append("(切换至下一环节)。\n");
                }

                // 新增：修订引导，防止盲目推进
                systemPromptBuf.append("- **动态调整**: 若观察结果（Observation）显示原计划已不可行，必须优先调用 `revise_plan` 修正后续步骤，严禁强行进入错误环节。\n");
                systemPromptBuf.append("- **禁止跳步**: 在更新进度前，禁止直接提供最终回答。");
            } else {
                systemPromptBuf.append("- **目标达成**: 计划看板已全部标记为 [√]。请综合上述执行过程中的所有观察结果，直接给出最终的详细回答。");
            }
        }

        if (trace.getSession().isPending()) {
            // 如果是从挂起状态恢复（例如 HITL 后继续）
            systemPromptBuf.append("\n\n[Human-In-The-Loop Context]\n" +
                    "用户已对你的执行流程进行了审核并准许继续。请结合最新的 Observation 反馈调整你的下一步策略。");
        }

        if (Assert.isNotEmpty(trace.getOptions().getOutputSchema())) {
            trace.getOptions().getChatModel().getDialect().prepareOutputSchemaInstruction(
                    trace.getOptions().getOutputSchema(),
                    systemPromptBuf);
        }

        if (trace.getProtocol() != null) {
            trace.getProtocol().injectAgentInstruction(context, agent, config.getLocale(),
                    systemPromptBuf);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent SystemPrompt rendered for trace [{}]: {}", trace.getAgentName(), systemPromptBuf);
        }


        // [逻辑 2.1: 上下文预处理] 在消息组装前触发，允许拦截器压缩 WorkingMemory
        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                entity.target.onReasonStart(trace, systemPromptBuf);
            }
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            return;
        }

        trace.newCurrentReasonId();
        String systemPromptStr = systemPromptBuf.toString();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(systemPromptStr));
        messages.addAll(trace.getWorkingMemory().getMessages());

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(new ReasonStartChunk(trace, systemPromptStr, messages));
        }

        // [逻辑 3: 模型交互] 执行物理请求并触发模型响应相关的拦截器
        long startMs = System.currentTimeMillis();
        ChatResponse response = callWithRetry(trace, messages);
        if(response == null || trace.getSession().isPending()){
            trace.setRoute(Agent.ID_END);
            return;
        }

        final AssistantMessage responseMessage;
        if (response.isStream()) {
            responseMessage = response.getAggregationMessage();
        } else {
            responseMessage = response.getMessage();
        }

        if(responseMessage == null){
            trace.setRoute(Agent.ID_END);
            return;
        }

        if (response.getUsage() != null) {
            trace.getMetrics().addUsage(response.getUsage());
        }

        // 触发推理审计事件（传递原始消息对象）
        long durationMs = System.currentTimeMillis() - startMs;
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onReasonEnd(trace, response, responseMessage, durationMs);
        }

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(new ReasonEndChunk(trace, response, responseMessage, durationMs));
        }

        if(trace.getSession().isPending()){
            return;
        }

        // 容错处理：模型响应内容及工具调用均为空时，引导其重新生成
        if (Assert.isEmpty(responseMessage.getResultContent()) && Assert.isEmpty(responseMessage.getToolCalls())) {
            if (trace.getEmptyRetryCounter().incrementAndGet() < 3) {
                //做3次重复
                LOG.warn("ReActAgent[{}] choices size:{}, responseMessage is empty: {}", trace.getAgentName(), response.getChoices().size(), responseMessage);

                if (Assert.isNotEmpty(responseMessage.getContent())) {
                    trace.getWorkingMemory().addMessage(responseMessage); //有些 llm 不能接受空消息
                    int retryCount = trace.getEmptyRetryCounter().get();
                    String formatFixPrompt = String.format(
                            "【系统指令：输出格式修正 (Format Correction)】\n" +
                            "您在第 %d 轮中输出了思考内容，但未包含有效的行动（Action）或最终答案（Final Answer）（第 %d 次尝试）。\n\n" +
                            "请检查您最近的 Observation 和之前的思考链，确保下一步操作或最终结论已明确给出。",
                            currentTurn, retryCount
                    );
                    trace.getWorkingMemory().addMessage(ChatMessage.ofUser(formatFixPrompt));
                } else {
                    // 反思机制：模型返回完全空响应（无内容、无工具调用）时， 通过自我反思提示引导模型回溯任务目标、审视历史轨迹并重新输出
                    int retryCount = trace.getEmptyRetryCounter().get();
                    String reflectPrompt = String.format(
                            "【系统指令：自我反思 (Self-Reflection)】\n" +
                            "您在第 %d 轮推理中返回了空响应（第 %d 次尝试），请立即启动自我反思：\n\n" +
                            "1. **回溯任务目标**：重新审视您最初被分配的任务核心目标，检查是否偏离了主线。\n" +
                            "2. **审视历史轨迹**：回顾最近的 Observation 和之前的思考链，定位导致空响应的原因。\n" +
                            "3. **策略修正**：若当前路径受阻，请果断切换思路或拆分为更小的子任务推进。\n",
                            currentTurn, retryCount
                    );
                    trace.getWorkingMemory().addMessage(ChatMessage.ofUser(reflectPrompt));
                }

                trace.setRoute(ReActAgent.ID_REASON);
            }

            return;
        } else {
            trace.getEmptyRetryCounter().set(0);
        }

        // [逻辑 3.5: 思考事件] 提取思考内容并触发 onThought 事件
        final String clearContent = responseMessage.hasContent() ? responseMessage.getResultContent() : "";
        final String thoughtContent;

        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            // 原生工具模式：非思考模式 LLM 的 getReasoning 可能为空，需回退到 extractThought
            thoughtContent = Utils.isNotEmpty(responseMessage.getReasoning())
                    ? responseMessage.getReasoning()
                    : extractThought(trace, clearContent);
        } else {
            // 文本结构模式：按 ReAct 协议 "Thought:" 解析
            thoughtContent = extractThought(trace, clearContent);
        }

        // 触发思考事件（合并原 onReasonEnd + onThought）
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onThought(trace, thoughtContent, responseMessage);
        }

        if(trace.getSession().isPending()){
            return;
        }

        if(trace.getOptions().getStreamSink() != null){
            trace.getOptions().getStreamSink().next(new ThoughtChunk(trace, response, responseMessage, thoughtContent));
        }

        trace.setLastReasonMessage(responseMessage);

        // [逻辑 4: 路由分发 - 基于原生工具调用协议]
        if (Assert.isNotEmpty(responseMessage.getToolCalls())) {
            trace.setRoute(ReActAgent.ID_ACTION);
            return;
        }

        // [逻辑 5: 路由判断 - 文本 ReAct 协议解析]
        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            if (Assert.isNotEmpty(clearContent)) {
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer(clearContent, false);
                return;
            }
        }

        // [逻辑 6: 决策流控]

        // 决策基准采用 clearContent，确保不受 <think> 标签内干扰词影响

        // 1. 优先判断任务是否结束（Finish）
        if (clearContent.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent), false);
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
        trace.setFinalAnswer(extractFinalAnswer(clearContent), false);
    }

    private @Nullable ChatResponse callWithRetry(ReActTrace trace, List<ChatMessage> messages) throws RuntimeException {
        ChatRequestDesc req = trace.getOptions().getChatModel()
                .prompt(messages)
                .options(o -> {
                    o.agentName(trace.getAgentName());

                    if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
                        o.toolAdd(trace.getOptions().getTools());
                        o.toolAdd(trace.getProtocolTools());
                    }

                    o.autoToolCall(false); // 强制由 Agent 框架接管工具链路管理
                    o.toolContextPut(trace.getOptions().getToolContext());

                    for(RankEntity<ReActInterceptor> entity :  trace.getOptions().getInterceptors()) {
                        //内部已支持启用控制
                        o.interceptorAdd(entity.index, entity.target);
                    }

                    if (trace.getOptions().getOutputSchema() != null) {
                        trace.getOptions().getChatModel().getDialect().prepareOutputFormatOptions(o);
                    }

                    o.optionSet(trace.getOptions().getModelOptions().options());

                    // 缓存配置：Agent 级优先，ChatModel 级次之
                    if(trace.getOptions().getCacheControl() != null) {
                        o.cacheControl(trace.getOptions().getCacheControl());
                    }
                });

        int maxRetries = trace.getOptions().getMaxRetries();

        try {
            return new RetryTask()
                    .maxRetries(maxRetries)
                    .initialDelayMs(trace.getOptions().getRetryDelayMs())
                    .onRetry((attempt,e)->{
                        LOG.warn("ReActAgent [{}] retry {}/{} due to: {}",
                                config.getName(), attempt, maxRetries, e.toString());
                    })
                    .callWithRetry(() -> {
                                final ChatResponse response;
                                if (trace.getOptions().getStreamSink() != null) {
                                    final FluxSink<AgentChunk> sink = trace.getOptions().getStreamSink();

                                    if (sink.isCancelled()) {
                                        return null;
                                    }

                                    response = req.stream()
                                            .takeUntil(r -> sink.isCancelled())
                                            .doOnNext(resp -> {
                                                if (!sink.isCancelled()) {
                                                    sink.next(new ReasonChunk(trace, resp, resp.getMessage()));
                                                }
                                            }).blockLast();
                                } else {
                                    response = req.call();
                                }

                                if (response == null || response.isEmpty()) {
                                    throw new LlmNoReturnException("The LLM did not return");
                                }

                                return response;
                            }
                    );
        } catch (Throwable e) {
            // 4. 异常后续处理（保留原有的文案逻辑）
            return handleLastException(trace, e);
        }
    }

    private ChatResponse handleLastException(ReActTrace trace, Throwable lastException) {
        if (lastException.getMessage() == null && lastException.getCause() != null) {
            lastException = lastException.getCause();
        }

        if (lastException instanceof InterruptedException || lastException.getCause() instanceof InterruptedException) {
            LOG.debug("InterruptedException");
            return null;
        } else {
            LOG.warn("ReActAgent [{}] call failed", config.getName(), lastException);
        }

        // 设置故障状态并终止路由
        trace.setRoute(Agent.ID_END);

        if (lastException instanceof LlmNoReturnException) {
            trace.setFinalAnswer("抱歉，模型服务没有内容返回。请稍后重试。");
        } else if (lastException instanceof TimeoutException ||
                lastException.getCause() instanceof TimeoutException) {
            trace.setFinalAnswer("抱歉，模型服务响应超时。请稍后重试。");
        } else if (lastException instanceof HttpResponseException) {
            HttpResponseException e2 = (HttpResponseException) lastException;
            trace.setFinalAnswer("抱歉，模型服务响应出错（CODE: " + e2.code() + "）。请稍后重试。");
        } else {
            // lastException 里可能有 html，不能直接展示
            trace.setFinalAnswer("抱歉，暂时无法使用模型服务（具体参考日志）。请稍后重试。");
        }

        return null;
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