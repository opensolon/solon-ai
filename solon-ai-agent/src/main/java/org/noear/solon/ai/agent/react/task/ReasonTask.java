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
import org.noear.solon.ai.agent.exception.LlmNoReturnException;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventGroup;
import org.noear.solon.ai.chat.event.ChatEventType;
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

import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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

        if (trace.hasStreamSink()) {
            trace.pushAgentEvent(new ReasonStartEvent(trace, systemPromptStr));
        }

        // [逻辑 3: 模型交互] 执行物理请求并触发模型响应相关的拦截器
        long startMs = System.currentTimeMillis();
        ChatResponse response = callWithRetry(trace, systemPromptStr);
        if(response == null || trace.getSession().isPending()){
            trace.setRoute(Agent.ID_END);
            return;
        }

        final AssistantMessage responseMessage = response.getMessage();

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

        if (trace.hasStreamSink()) {
            trace.pushAgentEvent(new ReasonEndEvent(trace, response, responseMessage, durationMs));
        }

        if(trace.getSession().isPending()){
            return;
        }

        // 容错处理：模型响应内容、工具调用与媒体均为空时，引导其重新生成
        // 纯生图等 media-only 响应不算空，避免被当成空响应重试
        // 注：text 用 trim 判定，纯空白（如 "\n\n"）等价于空，否则会 END 出一个空白答案
        //
        // 【保留原因】不要用 callWithRetry 里的 response.isEmpty()（第 469 行）替代本段：
        // 1) isEmpty() 是严格 null 判定（getContent()==null），拦不住纯空白文本，会在不重试的
        //    非流式路径下 END 出空白答案；
        // 2) 本段同时服务于流式与非流式两条路径（位于 callWithRetry 返回之后），且承担
        //    「格式修正/自我反思提示注入」的业务功能，删掉即丢失空响应重试能力。
        if (Assert.isBlank(responseMessage.getText())
                && Assert.isEmpty(responseMessage.getToolCalls())
                && !responseMessage.hasMedia()) {
            if (trace.getEmptyRetryCounter().incrementAndGet() < 3) {
                //做3次重复
                LOG.warn("ReActAgent[{}] responseMessage is empty: {}", trace.getAgentName(), responseMessage);

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
            } else {
                // 软上限变硬：重试耗尽仍空，直接 END，避免 route 保持 REASON 而每回合空转调模型直到 maxTurns。
                LOG.warn("ReActAgent[{}] empty response persists after {} retries, ending.",
                        trace.getAgentName(), trace.getEmptyRetryCounter().get());
                trace.setRoute(Agent.ID_END);

                // 纯思考轮（只有 reasoning 没有 Final Answer）时，思考内容其实已流式输出给前端，
                // 再用兜底文案覆盖会造成“内容都出来了却报未返回有效内容”的割裂；此处降级用思考内容作答。
                String thinkingFallback = responseMessage.getThinking();
                if (Assert.isBlank(thinkingFallback)) {
                    trace.setFinalAnswer("抱歉，模型多次未返回有效内容。请稍后重试。");
                } else {
                    trace.setFinalAnswer(thinkingFallback.trim(), false);
                }
            }

            return;
        } else {
            trace.getEmptyRetryCounter().set(0);
        }

        // [逻辑 3.5: 思考事件] 提取思考内容并触发 onThought 事件
        final String clearContent = responseMessage.hasContent() ? responseMessage.getText() : "";
        final String thoughtContent;

        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            // 原生工具模式：非思考模式 LLM 的 getReasoning 可能为空，需回退到 extractThought
            thoughtContent = Utils.isNotEmpty(responseMessage.getThinking())
                    ? responseMessage.getThinking()
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

        trace.setLastReasonMessage(responseMessage);

        // [逻辑 4: 路由分发 - 基于原生工具调用协议]
        if (Assert.isNotEmpty(responseMessage.getToolCalls())) {
            trace.setRoute(ReActAgent.ID_ACTION);
            return;
        }

        // [逻辑 5: 路由判断 - 文本 ReAct 协议解析]
        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            // 有文本，或仅有媒体（如生图）时，均视为有效最终输出
            if (Assert.isNotEmpty(clearContent) || responseMessage.hasMedia()) {
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer(clearContent, false);
                return;
            }
        }

        // [逻辑 6: 决策流控]

        // 决策基准采用 clearContent，确保不受 <think> 标签内干扰词影响

        // 1. 优先 Action：模型偶发同轮输出 Action + Final Answer 时，必须先执行工具（HITL 也依赖进 Action）
        if (clearContent.contains("Action:")) {
            String actionPart = clearContent.substring(clearContent.indexOf("Action:"));
            if (actionPart.length() > 7) {
                trace.setRoute(ReActAgent.ID_ACTION);
                return;
            }
        }
        
        // 2. 任务结束（Finish）
        if (clearContent.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(extractFinalAnswer(clearContent), false);
            return;
        }

        // 3. 兜底逻辑：既无明确工具调用也无完成标识，视为直接回复 Final Answer
        trace.setRoute(Agent.ID_END);
        trace.setFinalAnswer(extractFinalAnswer(clearContent), false);
    }

    private @Nullable ChatResponse callWithRetry(ReActTrace trace, String systemPrompt) throws RuntimeException {
        int maxRetries = trace.getOptions().getMaxRetries();
        final AtomicBoolean streamEmitted = new AtomicBoolean(false);

        try {
            return new RetryTask()
                    .maxRetries(maxRetries)
                    .initialDelayMs(trace.getOptions().getRetryDelayMs())
                    // 流式响应已经对外输出后，重试会造成重复内容；直接保留并抛出原始异常。
                    .retryIf(e -> {
                        // 1. 已经有流输出了，绝对不能重试，避免重复内容
                        if (streamEmitted.get()) {
                            return false;
                        }

                        // 2. 深度遍历 Cause 链，寻找特定异常或 HttpResponseException
                        Throwable cause = e;
                        while (cause != null) {
                            // 网络/超时/无返回：网络瞬态故障，安全重试
                            if (cause instanceof ConnectException ||
                                    cause instanceof TimeoutException ||
                                    cause instanceof LlmNoReturnException) {
                                return true;
                            }

                            // HTTP 状态码精准控制
                            if (cause instanceof HttpResponseException) {
                                int code = ((HttpResponseException) cause).code();
                                // 429 限流 或 5xx 服务端故障：允许重试；4xx 客户端参数错误：不重试
                                return code == 429 || code >= 500;
                            }

                            cause = cause.getCause();
                        }

                        // 3. 其他未知异常（如 IOException、SocketException 等），默认重试
                        return true;
                    })
                    .onRetry((attempt, e) -> {
                        boolean rebuilt = false;
                        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
                            if (!entity.target.isEnabled()) {
                                continue;
                            }
                            try {
                                if (entity.target.onReasonRetry(trace, e, attempt, systemPrompt)) {
                                    rebuilt = true;
                                }
                            } catch (Throwable interceptorError) {
                                LOG.warn("ReActAgent [{}] interceptor [{}] failed during reason retry; " +
                                                "continue with original retry flow",
                                        config.getName(), entity.target.getClass().getName(), interceptorError);
                            }
                        }
                        if (rebuilt) {
                            LOG.info("ReActAgent [{}] context rebuilt before retry {}/{}", config.getName(), attempt, maxRetries);
                        } else {
                            LOG.warn("ReActAgent [{}] retry {}/{} due to: {}",
                                    config.getName(), attempt, maxRetries, e.toString());
                        }
                    })
                    .callWithRetry(() -> {
                        List<ChatMessage> messages = new ArrayList<>();
                        messages.add(ChatMessage.ofSystem(systemPrompt));
                        messages.addAll(trace.getWorkingMemory().getMessages());

                        ChatRequestDesc req = buildRequest(trace, messages);
                        final ChatResponse response;
                        if (trace.hasStreamSink()) {
                            response = req.stream()
                                    .takeUntil(e -> trace.isStreamCancelled())
                                    .doOnNext(e -> {
                                        //重试门控与 AgentEvent 映射解耦：只要模型已产生任何内容侧输出，
                                        //重放整个请求就会造成重复内容或二次外部副作用。
                                        if (isRetryUnsafeOutput(e)) {
                                            streamEmitted.set(true);
                                        }

                                        //只上抛正文与思考增量（媒体取完成帧）：边界帧（TEXT_START/END 等）会与
                                        //DELTA 重复渲染，工具参数分片不是模型正文。ReasonTask 只输出思考与正文流；
                                        //工具/动作类事件一律由 ActionTask 发出（ActionStart/End、ToolCallStart/End）。
                                        if (e.is(ChatEventType.TEXT_DELTA, ChatEventType.THINKING_DELTA, ChatEventType.MEDIA_DONE)) {
                                            trace.pushAgentEvent(new ReasonDeltaEvent(trace, e));
                                        }
                                    })
                                    .filter(e -> e.is(ChatEventType.RESPONSE_END))
                                    .reduce((a, b) -> a.getType() == ChatEventType.RESPONSE_END ? a : b)
                                    .map(ChatEvent::getResponse)
                                    .block();
                        } else {
                            response = req.call();
                        }

                        if (response == null) {
                            //流中没有终态帧（订阅端取消时 takeUntil 提前完成流），归约结果为 null。
                            //这不是模型故障——重试同样会被立即取消，只会白花调用，
                            //所以直接返回 null，由 run() 以 END 收尾（不写兜底错误文案）。
                            //注意：非流式路径下 isStreamCancelled() 恒为 true（无 sink），必须先判 hasStreamSink()。
                            if (trace.hasStreamSink() && trace.isStreamCancelled()) {
                                return null;
                            }

                            throw new LlmNoReturnException("The LLM did not return");
                        }

                        if (response.isEmpty()) {
                            throw new LlmNoReturnException("The LLM did not return");
                        }
                        return response;
                    });
        } catch (Throwable e) {
            return handleLastException(trace, e);
        }
    }

    /**
     * 判断是否已经产生不可安全重放的模型输出。
     *
     * <p>这是重试策略，与上方「能否作为正文增量上抛」的白名单必然不同：工具参数分片不能进正文，却说明
     * 模型已经开始产出；服务端工具（联网搜索、代码执行）更是已经真实发生并计费，重放会二次执行。</p>
     *
     * <p>判定按<b>分组</b>而非具体类型：分组是闭集（9 个），新增事件类型只会落入既有分组，因此这里
     * 天然向前兼容——旧的 11 项显式白名单会在核心新增类型时静默漏判（如 {@code TOOL_CALL_CHUNK}、
     * {@code SERVER_TOOL_*}、{@code REFUSAL_DELTA} 就都不在其中）。</p>
     *
     * <p>排除的三个分组：{@code LIFECYCLE}（响应开始/状态/心跳/结束）、{@code STEP}（步边界）、
     * {@code META}（用量/错误/原始帧）——它们不载模型内容，也不产生外部副作用。</p>
     */
    private boolean isRetryUnsafeOutput(ChatEvent event) {
        return event != null && event.isGroup(
                ChatEventGroup.TEXT,
                ChatEventGroup.THINKING,
                ChatEventGroup.TOOL_CALL,
                ChatEventGroup.SERVER_TOOL,
                ChatEventGroup.MEDIA,
                ChatEventGroup.SAFETY);
    }

    private ChatRequestDesc buildRequest(ReActTrace trace, List<ChatMessage> messages) {
        return trace.getOptions().getChatModel()
                .prompt(messages)
                .options(o -> {
                    o.httpCustomizeAdd(trace.getOptions().getModelOptions().httpCustomize());
                    o.agentName(trace.getAgentName());
                    o.autoToolCall(false);
                    o.toolContextPut(trace.getOptions().getToolContext());

                    if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
                        o.toolAdd(trace.getOptions().getTools());
                        o.toolAdd(trace.getProtocolTools());
                    }

                    for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
                        o.interceptorAdd(entity.index, entity.target);
                    }

                    if (trace.getOptions().getOutputSchema() != null) {
                        trace.getOptions().getChatModel().getDialect().prepareOutputFormatOptions(o);
                    }

                    o.optionSet(trace.getOptions().getModelOptions().options());
                    if (trace.getOptions().getCacheControl() != null) {
                        o.cacheControl(trace.getOptions().getCacheControl());
                    }
                });
    }

    private ChatResponse handleLastException(ReActTrace trace, Throwable lastException) {
        // 1. 深度优先判断中断信号
        if (findCause(lastException, InterruptedException.class) != null) {
            LOG.debug("InterruptedException caught, agent execution aborted.");
            return null;
        }

        // 2. 打印完整异常堆栈
        LOG.warn("ReActAgent [{}] call failed", config.getName(), lastException);

        // 3. 设置终止状态
        trace.setRoute(Agent.ID_END);

        // 4. 精准提炼 Cause 并设置友好的用户提示
        HttpResponseException httpRespEx = findCause(lastException, HttpResponseException.class);

        if (findCause(lastException, LlmNoReturnException.class) != null) {
            trace.setFinalAnswer("抱歉，模型服务没有内容返回。请稍后重试。");
        } else if (isTimeoutException(lastException)) {
            trace.setFinalAnswer("抱歉，模型服务响应超时。请稍后重试。");
        } else if (isConnectException(lastException)) {
            trace.setFinalAnswer("抱歉，模型服务连接失败。请稍后重试。");
        } else if (httpRespEx != null) {
            trace.setFinalAnswer("抱歉，模型服务响应出错（CODE: " + httpRespEx.code() + "）。请稍后重试。");
        } else {
            // 兜底提示（防止敏感堆栈或 HTML 泄露给终端）
            trace.setFinalAnswer("抱歉，暂时无法使用模型服务（具体参考日志）。请稍后重试。");
        }

        return null;
    }

    private static boolean isTimeoutException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isConnectException(Throwable e) {
        return findCause(e, ConnectException.class) != null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable e, Class<T> clazz) {
        Throwable cause = e;
        while (cause != null) {
            if (clazz.isInstance(cause)) {
                return (T) cause;
            }
            cause = cause.getCause();
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