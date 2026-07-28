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

import org.noear.snack4.ONode;
import org.noear.snack4.json.JsonReader;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.core.exception.StatusException;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.*;

/**
 * ReAct 动作执行任务 (Action/Acting)
 * <p>核心职责：解析 Reason 阶段的指令，调用业务工具，并将 Observation（观测结果）回填至上下文。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ActionTask {
    private static final Logger LOG = LoggerFactory.getLogger(ActionTask.class);

    private final ReActAgentConfig config;

    public ActionTask(ReActAgentConfig config) {
        this.config = config;
    }

    public String name() {
        return ReActAgent.ID_ACTION;
    }

    public void run(ReActTrace trace, FlowContext context) throws Throwable {
        //重置默认路由
        trace.setRoute(ReActAgent.ID_REASON);

        if (LOG.isDebugEnabled()) {
            if (trace.getOptions().isPlanningMode()) {
                LOG.debug("ReActAgent [{}] action starting... Step: {}, Plan: {}",
                        config.getName(), trace.getStepCount(), trace.getPlanIndex() + 1);
            } else {
                LOG.debug("ReActAgent [{}] action starting (Step: {})...", config.getName(), trace.getStepCount());
            }
        }

        final TeamTrace parentTeamTrace = TeamTrace.getCurrent(context);
        AssistantMessage lastReason = trace.getLastReasonMessage();
        if (lastReason == null) {
            return;
        }

        try {
            if (Assert.isNotEmpty(lastReason.getToolCalls())) {
                // 1. 优先处理原生工具调用（Native Tool Calls）
                processNativeToolCall(lastReason, trace, parentTeamTrace);
            } else {
                // 2. 文本模式：解析模型输出中的 Action 块
                processTextModeAction(lastReason, trace, parentTeamTrace);
            }
        } finally {
            if (trace.getSession().isPending() == false) {
                // 不挂起时才推 ActionEnd，避免前端误判本轮 Action 已执行完毕
                for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
                    if (entity.target.isEnabled()) {
                        try {
                            entity.target.onActionEnd(trace);
                        } catch (Throwable e) {
                            LOG.error("Interceptor onActionEnd execution failed", e);
                        }
                    }
                }

                if (trace.hasStreamSink()) {
                    trace.pushAgentChunk(new ActionEndChunk(trace));
                }
            }

            //刷新快照
            trace.getSession().updateSnapshot();
        }
    }

    private ToolResult doAction(ReActTrace trace, ToolCall call, ToolExchanger exchanger, List<ChatMessage> toolResults) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Action for agent [{}], toolName:{}, args:{}", config.getName(), exchanger.getToolName(), exchanger.getArgs());
        }

        // 1. 触发前置生命周期
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            if (item.target.isEnabled()) {
                item.target.onToolCallStart(trace, exchanger);

                //@deprecated 4.0.4
                item.target.onAction(trace, exchanger);
            }
        }

        // 2. 如果前置拦截器直接挂起或截断了路由，立刻退出（交给 finally 闭环）
        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        // 3. 推送流式动作片
        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new ToolCallStartChunk(trace, exchanger.getCallId(), exchanger.getToolName(), exchanger.getArgs()));
            trace.pushAgentChunk(new ActionChunk(trace, exchanger.getCallId(), exchanger.getToolName(), exchanger.getArgs()));
        }

        long startMs = System.currentTimeMillis();
        ToolResult result = null;
        Throwable thrownError = null;

        try {
            // 4. 执行工具调用
            // HITL reject/skip 等会预填 exchanger.toolResult；有预填则跳过真实执行
            if (exchanger.getToolResult() == null) {
                result = executeTool(trace, exchanger);
            } else {
                result = exchanger.getToolResult();
            }

            if (result != null && !trace.getSession().isPending()) {
                // 保留完整 ToolResult（blocks / isError / metas），不再只写 String
                exchanger.setToolResult(result);
            }

            // 最终返回当前轮次处理后的最新观测值（完整结果，供 process* 判空）
            return exchanger.getToolResult();

        } catch (Throwable e) {
            thrownError = e;
            throw e;
        } finally {
            // ================== 【100% 强物理闭环】 ==================
            long durationMs = System.currentTimeMillis() - startMs;

            // Fallback 单工具挂起：不进执行、不推假 ToolCallEnd；仅调拦截器清理
            boolean pendingWithoutResult = thrownError == null
                    && exchanger.getToolResult() == null
                    && trace.getSession() != null
                    && trace.getSession().isPending();

            if (pendingWithoutResult) {
                for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
                    if (entity.target.isEnabled()) {
                        try {
                            entity.target.onToolCallEnd(trace, exchanger, null, null, durationMs);
                            entity.target.onObservation(trace, exchanger, null, null, durationMs);
                        } catch (Throwable e) {
                            LOG.error("Interceptor onToolCallEnd execution failed", e);
                        }
                    }
                }
            } else {
                ChatMessage observationMessage = null;

                if (thrownError != null) {
                    if (call == null) {
                        observationMessage = ChatMessage.ofUser("Observation: Execution critical error: " + thrownError.getMessage());
                    } else {
                        observationMessage = ChatMessage.ofTool(
                                ToolResult.error("Execution critical error: " + thrownError.getMessage()),
                                call.getName(),
                                call.getId(),
                                false
                        );
                    }
                } else if (exchanger.getToolResult() != null) {
                    observationMessage = buildObservationMessage(call, exchanger);
                }

                // 无论正常结束还是中途抛出 critical error，走统一清理与下发逻辑
                handleSingleObservation(trace, exchanger, observationMessage, durationMs, thrownError, toolResults);
            }
        }
    }

    /**
     * 用完整 ToolResult 构造 observation，保留 media / isError / metas。
     */
    private ChatMessage buildObservationMessage(ToolCall call, ToolExchanger exchanger) {
        ToolResult toolResult = exchanger.getToolResult();
        if (call == null) {
            // 文本模式：无 ToolCall id，退化为 User observation（文本投影）
            String text = toolResult == null ? null : toolResult.getContent();
            if (text == null && toolResult != null) {
                text = toolResult.toString();
            }
            return ChatMessage.ofUser("Observation: " + (text == null ? "" : text));
        }
        return ChatMessage.ofTool(
                toolResult,
                call.getName(),
                call.getId(),
                exchanger.isReturnDirect());
    }

    /**
     * 处理标准 ToolCall 协议调用
     */
    private void processNativeToolCall(AssistantMessage lastReason, ReActTrace trace, TeamTrace parentTeamTrace) throws Throwable {
        Map<ToolCall, ToolExchanger> toolExchangerMap = new LinkedHashMap<>();

        for (ToolCall call : lastReason.getToolCalls()) {
            // 拷贝参数，避免 HITL 改参加污染 ToolCall.arguments（会话/审计看到的是模型原始参数）
            Map<String, Object> args = new HashMap<>(
                    call.getArguments() == null ? Collections.emptyMap() : call.getArguments());
            ToolExchanger exchanger = new ToolExchanger(call.getUuid(), call.getName(), args);
            toolExchangerMap.put(call, exchanger);
        }

        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                entity.target.onActionStart(trace, toolExchangerMap.values());
            }
        }

        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return;
        }

        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new ActionStartChunk(trace, toolExchangerMap.values()));
        }

        List<ChatMessage> toolResults = new ArrayList<>();

        for (Map.Entry<ToolCall, ToolExchanger> entry : toolExchangerMap.entrySet()) {
            ToolResult result = doAction(trace, entry.getKey(), entry.getValue(), toolResults);
            if (result == null) {
                // pending / critical：不写不完整的成套 WM，交给上层
                return;
            }
            // Feedback 等已终止路径：停止后续工具，但仍要落库已执行部分
            if (Agent.ID_END.equals(trace.getRoute())) {
                break;
            }
        }

        if (toolResults.size() > 0) {
            //确保“成套”出现，避免错位
            trace.getWorkingMemory().addMessage(lastReason);
            trace.getWorkingMemory().addMessage(toolResults);
        }

        // 本轮全部 returnDirect 且成功时直接结束（对齐 Chat 层）
        applyReturnDirectIfEligible(trace, toolExchangerMap.values());
    }

    /**
     * 解析并执行文本模式下的 Action 指令
     * <p>核心逻辑：从"全执行后拼接"改为"逐个执行并即时回填与反馈"</p>
     */
    private void processTextModeAction(AssistantMessage lastReason, ReActTrace trace, TeamTrace parentTeamTrace) throws Throwable {
        String lastContent = lastReason.getResultContent();
        if (Assert.isEmpty(lastContent)) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing text mode action for agent [{}].", config.getName());
        }

        // key = callId（toolName + 解析序位，同文本可复现）；禁止仅用 toolName，否则同名多 Action 会被覆盖
        Map<String, ToolExchanger> toolExchangerMap = new LinkedHashMap<>();
        List<ChatMessage> toolResults = new ArrayList<>();
        int actionLabelIndex = lastContent.indexOf("Action:");

        if (actionLabelIndex >= 0) {
            // 尝试寻找 JSON 起始位置
            int jsonStart = lastContent.indexOf('{', actionLabelIndex + 7);
            int index = 0;
            if (jsonStart >= 0) {
                // 情况 A：JSON 模式流式解析
                StringReader sr = new StringReader(lastContent.substring(jsonStart));
                JsonReader jsonReader = new JsonReader(sr);

                while (true) {
                    try {
                        ONode actionNode = jsonReader.readNext();
                        if (actionNode == null || !actionNode.isObject()) {
                            break;
                        }

                        String toolName = actionNode.get("name").getString();
                        ONode argsNode = actionNode.get("arguments");
                        Map<String, Object> rawArgs = argsNode.isObject() ? argsNode.toBean(Map.class) : null;
                        Map<String, Object> args = new HashMap<>(rawArgs == null ? Collections.emptyMap() : rawArgs);

                        String callId = toolName + "-" + (index++);
                        ToolExchanger exchanger = new ToolExchanger(callId, toolName, args);
                        toolExchangerMap.put(callId, exchanger);

                    } catch (Throwable e) {
                        // 解析异常回传 (优化点 2)
                        ChatMessage observationMessage = ChatMessage.ofUser("Observation: Error parsing Action JSON: " + e.getMessage());
                        toolResults.add(observationMessage);
                        break;
                    }
                }
            } else {
                // 情况 B：纯文本模式 Action: toolName
                String toolName = lastContent.substring(actionLabelIndex + 7).trim();
                if (trace.getOptions().getTool(toolName) != null || FeedbackTool.TOOL_NAME.equals(toolName)) {
                    Map<String, Object> args = new HashMap<>();

                    String callId = toolName + "-" + (index++);
                    ToolExchanger exchanger = new ToolExchanger(callId, toolName, args);
                    toolExchangerMap.put(callId, exchanger);
                }
            }
        }

        //----------
        if (toolExchangerMap.isEmpty()) {
            // 模型声明了 Action 但未解析成功，或无 Action 声明
            if (actionLabelIndex >= 0) {
                toolResults.add(ChatMessage.ofUser(
                        "Observation: No valid Action format detected. Use JSON: {\"name\": \"...\", \"arguments\": {}}"));
            }
            if (toolResults.size() > 0) {
                trace.getWorkingMemory().addMessage(lastReason);
                trace.getWorkingMemory().addMessage(toolResults);
            }
            return;
        }

        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                entity.target.onActionStart(trace, toolExchangerMap.values());
            }
        }

        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return;
        }

        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new ActionStartChunk(trace, toolExchangerMap.values()));
        }

        for (ToolExchanger exchanger : toolExchangerMap.values()) {
            ToolResult result = doAction(trace, null, exchanger, toolResults);
            if (result == null) {
                return;
            }
            if (Agent.ID_END.equals(trace.getRoute())) {
                break;
            }
        }

        if (toolResults.size() > 0) {
            //确保“成套”出现，避免错位
            trace.getWorkingMemory().addMessage(lastReason);
            trace.getWorkingMemory().addMessage(toolResults);
        }

        applyReturnDirectIfEligible(trace, toolExchangerMap.values());
    }

    /**
     * 优化点 4：统一 Observation 落地逻辑。
     * 改变了原有 StringBuilder 拼接逻辑，直接进行 WorkingMemory 入库并触发流
     */
    private void handleSingleObservation(ReActTrace trace, ToolExchanger toolExchanger,
                                         ChatMessage observationMessage, long durationMs,
                                         Throwable error, List<ChatMessage> toolResults) {

        // 先走拦截器（HITL 可在 onToolCallEnd 注入批准 Note 到 exchanger.result）
        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                try {
                    entity.target.onToolCallEnd(trace, toolExchanger, observationMessage, error, durationMs);

                    //@deprecated 4.0.4
                    entity.target.onObservation(trace, toolExchanger, observationMessage, error, durationMs);
                } catch (Throwable e) {
                    LOG.error("Interceptor onObservation execution failed", e);
                }
            }
        }

        // 拦截器可能改写了 result（如批准 Note）：按最终 toolResult 重建 observation，确保进 WM / 流式
        if (error == null && toolExchanger.getToolResult() != null) {
            observationMessage = rebuildObservationIfNeeded(observationMessage, toolExchanger);
        }

        if (observationMessage == null) {
            if (error == null) {
                error = new RuntimeException("The tool task has been interrupted or pending.");
            }
            observationMessage = ChatMessage.ofAssistant("");
        } else if (toolResults != null) {
            toolResults.add(observationMessage);
        }

        // 流式客户端通知闭环（使用最终 observation）
        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new ToolCallEndChunk(trace, toolExchanger.getCallId(), toolExchanger.getToolName(), toolExchanger.getArgs(), observationMessage, error, durationMs));

            //@deprecated 4.0.4
            trace.pushAgentChunk(new ObservationChunk(trace, toolExchanger.getCallId(), toolExchanger.getToolName(), toolExchanger.getArgs(), observationMessage, error, durationMs));
        }
    }

    /**
     * 若 interceptor 改写了 exchanger 文本，则按最终 toolResult 重建 observation（保留 media / isError）。
     */
    private ChatMessage rebuildObservationIfNeeded(ChatMessage observationMessage, ToolExchanger toolExchanger) {
        ToolResult toolResult = toolExchanger.getToolResult();
        if (toolResult == null) {
            return observationMessage;
        }

        String finalContent = toolResult.getContent();
        boolean textMode = observationMessage == null
                || (observationMessage.getContent() != null
                && observationMessage.getContent().startsWith("Observation: "));

        if (textMode) {
            String projected = finalContent;
            if (projected == null) {
                projected = toolResult.toString();
            }
            String expected = "Observation: " + (projected == null ? "" : projected);
            if (observationMessage != null && expected.equals(observationMessage.getContent())) {
                return observationMessage;
            }
            return ChatMessage.ofUser(expected);
        }

        // native ToolMessage：content 一致且无 media 差异时复用
        if (observationMessage instanceof ToolMessage) {
            ToolMessage tm = (ToolMessage) observationMessage;
            boolean sameContent = Objects.equals(finalContent, tm.getContent());
            boolean sameReturnDirect = tm.isReturnDirect() == toolExchanger.isReturnDirect();
            boolean sameBlockSize = tm.getBlocks() != null
                    && tm.getBlocks().size() == toolResult.getBlocks().size();
            if (sameContent && sameReturnDirect && sameBlockSize) {
                return observationMessage;
            }
            return ChatMessage.ofTool(
                    toolResult,
                    toolExchanger.getToolName(),
                    tm.getToolCallId(),
                    toolExchanger.isReturnDirect());
        }

        return ChatMessage.ofTool(
                toolResult,
                toolExchanger.getToolName(),
                null,
                toolExchanger.isReturnDirect());
    }

    /**
     * 查找并执行工具。
     * <p>returnDirect 仅在真实成功后标记到 exchanger；不在此处结束 ReAct 循环，
     * 由 {@link #applyReturnDirectIfEligible} 在本轮全部工具执行完毕后统一判定（对齐 Chat 层）。</p>
     */
    private ToolResult executeTool(ReActTrace trace, ToolExchanger exchanger) {
        if (FeedbackTool.TOOL_NAME.equals(exchanger.getToolName())) {
            // Feedback：保留独立结束语义（interrupt + abnormal FinalAnswer），不走通用 returnDirect 汇总
            String reason = (String) exchanger.getArgs().get("reason");
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(reason);
            trace.getContext().interrupt();
            return ToolResult.success(reason);
        }

        FunctionTool tool = trace.getOptions().getTool(exchanger.getToolName());
        if (tool == null) {
            tool = trace.getProtocolTool(exchanger.getToolName());
        }

        if (tool != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent [{}] invoking tool start [{}], args: {}", config.getName(), exchanger.getToolName(), exchanger.getArgs());
                }

                //合并工具上下文和参数，形成请求
                final ToolRequest toolReq = new ToolRequest(null, trace.getOptions().getToolContext(), exchanger.getArgs());
                final ToolResult result;
                if (trace.getOptions().getInterceptors().isEmpty()) {
                    result = tool.call(toolReq.getArgs());
                } else {
                    result = new ToolChain(trace.getOptions().getInterceptors(), tool).doIntercept(toolReq);
                }
                trace.incrementToolCallCount();

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent [{}] invoking tool end [{}], args: {}", config.getName(), exchanger.getToolName(), exchanger.getArgs());
                }

                // 仅「真实成功」标记：声明 returnDirect、非 error、且有可交付内容（文本非空或含 media）。
                // Schema/执行异常走 catch 不标记；空串无 media 不标记，避免元数据「可直返却未直返」。
                if (tool.returnDirect() && isSuccessfulDeliverable(result)) {
                    exchanger.setReturnDirect(true);
                }

                return result;
            } catch (IllegalArgumentException | StatusException e) {
                // 引导模型自愈：返回 Schema 错误提示（不直返）
                return ToolResult.success("Invalid arguments for [" + exchanger.getToolName() + "]. Expected Schema: " + tool.inputSchema() + ". Error: " + e.getMessage());
            } catch (Throwable e) {
                LOG.error("Agent [" + config.getName() + "] tool [" + exchanger.getToolName() + "] execution failed", e);
                return ToolResult.success("Execution error in tool [" + exchanger.getToolName() + "]: " + e.getMessage());
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("Agent [{}] tool [{}] not found", config.getName(), exchanger.getToolName());
        }

        return ToolResult.success("Tool [" + exchanger.getToolName() + "] not found.");
    }

    /**
     * 结果是否「真实成功且可交付」：非 null、非 error，且（文本非空 或 含非文本 media）。
     * <p>纯 media（生图）允许直返；空串无 media 不直返。</p>
     */
    private static boolean isSuccessfulDeliverable(ToolResult result) {
        if (result == null || result.isError()) {
            return false;
        }
        if (Assert.isNotEmpty(result.getContent())) {
            return true;
        }
        // media-only：有非文本 block 也算可交付
        if (result.getBlocks() != null) {
            for (ContentBlock block : result.getBlocks()) {
                if (!(block instanceof TextBlock)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 本轮工具全部 returnDirect 且均成功时，将结果拼接为 FinalAnswer 并 END。
     * <p>对齐 {@code ChatRequestDescDefault#buildToolMessage}：仅当全员 isReturnDirect 时才直返；
     * 任一 false / 失败 / {@link ToolResult#error} / 空结果 则保持 Observation → Reason。
     * Feedback 已设 END 时跳过。纯 media 可直返（FinalAnswer 文本可为空，media 上浮到 lastReason）。</p>
     */
    private void applyReturnDirectIfEligible(ReActTrace trace, Collection<ToolExchanger> exchangers) {
        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return;
        }
        if (exchangers == null || exchangers.isEmpty()) {
            return;
        }

        StringBuilder joined = new StringBuilder();
        List<ContentBlock> mediaBlocks = new ArrayList<>();

        for (ToolExchanger exchanger : exchangers) {
            // isReturnDirect=false 覆盖：未声明、执行异常、ToolResult.error、空串、HITL 预填跳过执行等
            if (!exchanger.isReturnDirect()) {
                return;
            }
            ToolResult tr = exchanger.getToolResult();
            if (tr == null || !isSuccessfulDeliverable(tr)) {
                return;
            }

            String content = tr.getContent();
            if (Assert.isNotEmpty(content)) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(content);
            }

            // 收集非文本 media，供终态 AssistantMessage 保留（对齐 SimpleAgent / ReActAgent 收口）
            if (tr.getBlocks() != null) {
                for (ContentBlock block : tr.getBlocks()) {
                    if (!(block instanceof TextBlock)) {
                        mediaBlocks.add(block);
                    }
                }
            }
        }

        // 业务工具正常直返：abnormal=false（与 Feedback 单参 setFinalAnswer→abnormal 区分）
        // 纯 media 时 finalAnswer 可为 ""，ReActAgent.buildFinalAssistantMessage 靠 lastReason.media 收口
        String finalText = joined.toString();
        trace.setFinalAnswer(finalText, false);
        trace.setRoute(Agent.ID_END);

        if (!mediaBlocks.isEmpty()) {
            // 把 tool media 挂到 lastReason，复用 ReActAgent 终态 media 保留逻辑
            attachMediaToLastReason(trace, finalText, mediaBlocks);
        }
    }

    /**
     * returnDirect 工具的 media 上浮到 lastReason，供 {@code ReActAgent#buildFinalAssistantMessage} 保留。
     */
    private void attachMediaToLastReason(ReActTrace trace, String finalText, List<ContentBlock> mediaBlocks) {
        AssistantMessage last = trace.getLastReasonMessage();
        List<ContentBlock> merged = new ArrayList<>(mediaBlocks);
        if (last != null && last.getBlocks() != null) {
            for (ContentBlock block : last.getBlocks()) {
                if (!(block instanceof TextBlock) && !merged.contains(block)) {
                    merged.add(block);
                }
            }
        }
        // 文本用 finalAnswer；blocks 只带非文本 media，避免 TextBlock 重复
        // lastReason 此处仅作 media 载体；完整 tool_calls 仍以 WM 成套消息为准
        String text = Assert.isEmpty(finalText)
                ? (last != null && last.getContent() != null ? last.getContent() : "")
                : finalText;
        AssistantMessage withMedia = ChatMessage.ofAssistant(text, merged);
        if (last != null && Utils.isNotEmpty(last.getMetadata())) {
            withMedia.addMetadata(last.getMetadata());
        }
        trace.setLastReasonMessage(withMedia);
    }
}
