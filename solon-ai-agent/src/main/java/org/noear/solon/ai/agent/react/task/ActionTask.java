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
            //刷新快照
            trace.getSession().updateSnapshot();
        }
    }

    private ToolResult doAction(ReActTrace trace, String toolName, Map<String, Object> args, List<ChatMessage> toolResults, ToolCall call) {
        ToolResult result = doAction0(trace, toolName, args);

        if (result == null) {
            handleSingleObservation(trace, toolName, args, null, null);
        } else {
            if (call == null) {
                ChatMessage observationMessage = ChatMessage.ofUser("Observation: " + result.getContent());
                handleSingleObservation(trace, toolName, args, observationMessage, toolResults);
            } else {
                // 协议闭环：回填 ToolMessage
                ToolMessage toolMessage = ChatMessage.ofTool(result, call.getName(), call.getId(), false);
                handleSingleObservation(trace, call.getName(), args, toolMessage, toolResults);
            }
        }

        return result;
    }


    private ToolResult doAction0(ReActTrace trace, String toolName, Map<String, Object> args) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Action for agent [{}], toolName:{}, args:{}", config.getName(), toolName, args);
        }

        trace.setLastObservation(null);
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onActionStart(trace, toolName, args);
        }

        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(
                    new ActionStartChunk(trace, toolName, args));
        }

        // 4. 执行工具
        final ToolResult result;
        long start = System.currentTimeMillis();
        if (Assert.isEmpty(trace.getLastObservation())) {
            result = executeTool(trace, toolName, args);
        } else {
            //可能会在 onAction 里产生 Observation
            result = ToolResult.success(trace.getLastObservation());
        }

        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        final long durationMs = System.currentTimeMillis() - start;

        trace.setLastObservation(result.getContent());

        // 5. 触发 Observation 拦截 (内容是纯的)
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onObservation(trace, toolName, result.getContent(), durationMs);
        }

        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        return ToolResult.success(trace.getLastObservation());
    }

    /**
     * 处理标准 ToolCall 协议调用
     */
    private void processNativeToolCall(AssistantMessage lastReason, ReActTrace trace, TeamTrace parentTeamTrace) throws Throwable {
        List<ChatMessage> toolResults = new ArrayList<>();

        for (ToolCall call : lastReason.getToolCalls()) {
            Map<String, Object> args = (call.getArguments() == null) ? new HashMap<>() : call.getArguments();

            // 触发 Action 生命周期拦截
            ToolResult result = doAction(trace, call.getName(), args, toolResults, call);
            if (result == null) {
                return;
            }
        }

        if (toolResults.size() > 0) {
            //确保“成套”出现，避免错位
            trace.getWorkingMemory().addMessage(lastReason);
            trace.getWorkingMemory().addMessage(toolResults);
        }
    }

    /**
     * 解析并执行文本模式下的 Action 指令
     */
    /**
     * 解析并执行文本模式下的 Action 指令
     * 核心逻辑优化：从“全执行后拼接”改为“逐个执行并即时回填与反馈”
     */
    private void processTextModeAction(AssistantMessage lastReason, ReActTrace trace, TeamTrace parentTeamTrace) throws Throwable {
        String lastContent = lastReason.getResultContent();
        if (Assert.isEmpty(lastContent)) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing text mode action for agent [{}].", config.getName());
        }

        List<ChatMessage> toolResults = new ArrayList<>();
        int actionLabelIndex = lastContent.indexOf("Action:");
        boolean foundAny = false;

        if (actionLabelIndex >= 0) {
            // 尝试寻找 JSON 起始位置
            int jsonStart = lastContent.indexOf('{', actionLabelIndex + 7);

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

                        foundAny = true;
                        String toolName = actionNode.get("name").getString();
                        ONode argsNode = actionNode.get("arguments");
                        Map<String, Object> args = argsNode.isObject() ? argsNode.toBean(Map.class) : new HashMap<>();

                        ToolResult result = doAction(trace, toolName, args, toolResults, null);
                        if (result == null) {
                            return;
                        }
                    } catch (Throwable e) {
                        // 解析异常回传 (优化点 2)
                        ChatMessage observationMessage = ChatMessage.ofUser("Observation: Error parsing Action JSON: " + e.getMessage());
                        handleSingleObservation(trace, null, null, observationMessage, toolResults);
                        foundAny = true;
                        break;
                    }
                }
            } else {
                // 情况 B：纯文本模式 Action: toolName
                String toolName = lastContent.substring(actionLabelIndex + 7).trim();
                if (trace.getOptions().getTool(toolName) != null || FeedbackTool.TOOL_NAME.equals(toolName)) {
                    foundAny = true;
                    Map<String, Object> args = new HashMap<>();

                    ToolResult result = doAction(trace, toolName, args, toolResults, null);
                    if (result == null) {
                        return;
                    }
                }
            }
        }

        // 容错处理：如果声明了 Action 但没解析成功，或模型说话不规整 (优化点 3)
        if (!foundAny && actionLabelIndex >= 0) {
            ChatMessage chatMessage = ChatMessage.ofUser("Observation: No valid Action format detected. Use JSON: {\"name\": \"...\", \"arguments\": {}}");
            handleSingleObservation(trace, null, null,chatMessage , toolResults);
        }

        if (toolResults.size() > 0) {
            //确保“成套”出现，避免错位
            trace.getWorkingMemory().addMessage(lastReason);
            trace.getWorkingMemory().addMessage(toolResults);
        }
    }

    /**
     * 优化点 4：统一 Observation 落地逻辑。
     * 改变了原有 StringBuilder 拼接逻辑，直接进行 WorkingMemory 入库并触发流
     */
    private void handleSingleObservation(ReActTrace trace, String toolName, Map<String, Object> args, ChatMessage observationMessage, List<ChatMessage> toolResults) {
        Throwable error = null;

        if (observationMessage == null) {
            error = new RuntimeException("The tool has been interrupted.");
            observationMessage = ChatMessage.ofAssistant("");
        } else {
            toolResults.add(observationMessage);
        }

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(
                    new ActionEndChunk(trace, toolName, args, observationMessage, error));
        }

        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            entity.target.onActionEnd(trace, toolName, args, observationMessage, error);
        }
    }

    /**
     * 查找并执行工具
     *
     * @return 工具输出的字符串结果
     */
    private ToolResult executeTool(ReActTrace trace, String name, Map<String, Object> args) {
        if (FeedbackTool.TOOL_NAME.equals(name)) {
            String reason = (String) args.get("reason");
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(reason);
            trace.getContext().interrupt();
            return ToolResult.success(reason);
        }

        FunctionTool tool = trace.getOptions().getTool(name);
        if (tool == null) {
            tool = trace.getProtocolTool(name);
        }

        if (tool != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent [{}] invoking tool start [{}], args: {}", config.getName(), name, args);
                }

                //合并工具上个文和参数，形成请求
                final ToolRequest toolReq = new ToolRequest(null, trace.getOptions().getToolContext(), args);
                final ToolResult result;
                if (trace.getOptions().getInterceptors().isEmpty()) {
                    result = tool.call(toolReq.getArgs());
                } else {
                    result = new ToolChain(trace.getOptions().getInterceptors(), tool).doIntercept(toolReq);
                }
                trace.incrementToolCallCount();

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent [{}] invoking tool end [{}], args: {}", config.getName(), name, args);
                }

                return result;
            } catch (IllegalArgumentException | StatusException e) {
                // 引导模型自愈：返回 Schema 错误提示
                return ToolResult.success("Invalid arguments for [" + name + "]. Expected Schema: " + tool.inputSchema() + ". Error: " + e.getMessage());
            } catch (Throwable e) {
                LOG.error("Agent [" + config.getName() + "] tool [" + name + "] execution failed", e);
                return ToolResult.success("Execution error in tool [" + name + "]: " + e.getMessage());
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("Agent [{}] tool [{}] not found", config.getName(), name);
        }

        return ToolResult.success("Tool [" + name + "] not found.");
    }
}