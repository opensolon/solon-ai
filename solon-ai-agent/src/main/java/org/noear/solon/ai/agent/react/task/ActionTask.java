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
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 动作执行任务 (Action/Acting)
 * <p>核心职责：解析 Reason 阶段的指令，调用业务工具，并将 Observation（观测结果）回填至上下文。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ActionTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ActionTask.class);

    private final ReActAgentConfig config;

    public ActionTask(ReActAgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return ReActAgent.ID_ACTION;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_UNIT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);
        final TeamTrace parentTeamTrace = TeamTrace.getCurrent(context);

        if (LOG.isDebugEnabled()) {
            if (trace.getOptions().isPlanningMode()) {
                LOG.debug("ReActAgent [{}] action starting... Step: {}, Plan: {}",
                        config.getName(), trace.getStepCount(), trace.getPlanIndex() + 1);
            } else {
                LOG.debug("ReActAgent [{}] action starting (Step: {})...", config.getName(), trace.getStepCount());
            }
        }

        AssistantMessage lastAssistant = trace.getLastReasonMessage();
        AtomicBoolean lastAssistantAdded = new AtomicBoolean(false);

        // 1. 优先处理原生工具调用（Native Tool Calls）
        if (lastAssistant != null && Assert.isNotEmpty(lastAssistant.getToolCalls())) {
            for (ToolCall call : lastAssistant.getToolCalls()) {
                processNativeToolCall(node, call, trace, parentTeamTrace, lastAssistantAdded);
                if (Agent.ID_END.equals(trace.getRoute())) {
                    break;
                }
            }
            return;
        }

        // 2. 文本模式：解析模型输出中的 Action 块
        processTextModeAction(node, trace, parentTeamTrace, lastAssistantAdded);
    }


    private String doAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        trace.setLastObservation(null);
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onAction(trace, toolName, args);
        }

        if (trace.isPending()) {
            return null;
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        // 4. 执行工具
        final String result;
        if (Assert.isEmpty(trace.getLastObservation())) {
            result = executeTool(trace, toolName, args);
        } else {
            //可能会在 onAction 里产生 Observation
            result = trace.getLastObservation();
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        trace.setLastObservation(result);

        // 5. 触发 Observation 拦截 (内容是纯的)
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onObservation(trace, toolName, result);
        }

        if (trace.isPending()) {
            return null;
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        return trace.getLastObservation();
    }

    /**
     * 处理标准 ToolCall 协议调用
     */
    private void processNativeToolCall(Node node, ToolCall call, ReActTrace trace, TeamTrace parentTeamTrace, AtomicBoolean lastAssistantAdded) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing native tool call for agent [{}]: {}.", config.getName(), call);
        }

        Map<String, Object> args = (call.getArguments() == null) ? new HashMap<>() : call.getArguments();

        // 触发 Action 生命周期拦截
        String result = doAction(trace, call.getName(), args);
        if (result == null) {
            return;
        }

        // 协议闭环：回填 ToolMessage
        ToolMessage toolMessage = ChatMessage.ofTool(result, call.getName(), call.getId());
        if (lastAssistantAdded.compareAndSet(false, true)) {
            trace.getWorkingMemory().addMessage(trace.getLastReasonMessage());
        }

        trace.getWorkingMemory().addMessage(toolMessage);

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(
                    new ActionChunk(node, trace, call.getName(), args, toolMessage));
        }
    }

    /**
     * 解析并执行文本模式下的 Action 指令
     */
    /**
     * 解析并执行文本模式下的 Action 指令
     * 核心逻辑优化：从“全执行后拼接”改为“逐个执行并即时回填与反馈”
     */
    private void processTextModeAction(Node node, ReActTrace trace, TeamTrace parentTeamTrace, AtomicBoolean lastAssistantAdded) throws Throwable {
        AssistantMessage lastReason = trace.getLastReasonMessage();
        if (lastReason == null) {
            return;
        }

        String lastContent = lastReason.getResultContent();
        if (Assert.isEmpty(lastContent)) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing text mode action for agent [{}].", config.getName());
        }

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

                        // 执行并即时处理 (优化点 1)
                        handleSingleAction(node, trace, toolName, args, lastAssistantAdded);

                    } catch (Exception e) {
                        // 解析异常回传 (优化点 2)
                        handleSingleObservation(node, trace, null, null, "Observation: Error parsing Action JSON: " + e.getMessage(), lastAssistantAdded);
                        foundAny = true;
                        break;
                    }
                }
            } else {
                // 情况 B：纯文本模式 Action: toolName
                String toolName = lastContent.substring(actionLabelIndex + 7).trim();
                if (trace.getOptions().getTool(toolName) != null || FeedbackTool.TOOL_NAME.equals(toolName)) {
                    foundAny = true;
                    handleSingleAction(node, trace, toolName, new HashMap<>(), lastAssistantAdded);
                }
            }
        }

        // 容错处理：如果声明了 Action 但没解析成功，或模型说话不规整 (优化点 3)
        if (!foundAny && actionLabelIndex >= 0) {
            handleSingleObservation(node, trace, null, null, "Observation: No valid Action format detected. Use JSON: {\"name\": \"...\", \"arguments\": {}}", lastAssistantAdded);
        }
    }

    /**
     * 优化点 1：提取独立执行方法，确保 Observation 即时生成
     */
    private void handleSingleAction(Node node, ReActTrace trace, String toolName, Map<String, Object> args, AtomicBoolean lastAssistantAdded) throws Throwable {
        String result = doAction(trace, toolName, args);
        if (result != null) {
            handleSingleObservation(node, trace, toolName, args, "Observation: " + result, lastAssistantAdded);
        }
    }

    /**
     * 优化点 4：统一 Observation 落地逻辑。
     * 改变了原有 StringBuilder 拼接逻辑，直接进行 WorkingMemory 入库并触发流
     */
    private void handleSingleObservation(Node node, ReActTrace trace, String toolName, Map<String, Object> args, String observationContent, AtomicBoolean lastAssistantAdded) {
        ChatMessage chatMessage = ChatMessage.ofUser(observationContent);

        // 回填 Reason 和本次 Observation
        // 这样做能保证上下文的 Thought-Action-Observation 结构始终稳固
        if (lastAssistantAdded.compareAndSet(false, true)) {
            trace.getWorkingMemory().addMessage(trace.getLastReasonMessage());
        }
        trace.getWorkingMemory().addMessage(chatMessage);

        // 优化点 5：即时触发 StreamSink。
        // 在旧逻辑中，多工具并发时用户只能看到最后的结果，现在可以逐个看到每个工具的输出
        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(
                    new ActionChunk(node, trace, toolName, args, chatMessage));
        }
    }

    /**
     * 查找并执行工具
     *
     * @return 工具输出的字符串结果
     */
    private String executeTool(ReActTrace trace, String name, Map<String, Object> args) {
        if (FeedbackTool.TOOL_NAME.equals(name)) {
            String reason = (String) args.get("reason");
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(reason);
            trace.getContext().interrupt();
            return reason;
        }

        FunctionTool tool = trace.getOptions().getTool(name);
        if (tool == null) {
            tool = trace.getProtocolTool(name);
        }

        if (tool != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent [{}] invoking tool [{}], args: {}", config.getName(), name, args);
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


                return result.getContent();
            } catch (IllegalArgumentException e) {
                // 引导模型自愈：返回 Schema 错误提示
                return "Invalid arguments for [" + name + "]. Expected Schema: " + tool.inputSchema() + ". Error: " + e.getMessage();
            } catch (Throwable e) {
                LOG.error("Agent [" + config.getName() + "] tool [" + name + "] execution failed", e);
                return "Execution error in tool [" + name + "]: " + e.getMessage();
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("Agent [{}] tool [{}] not found", config.getName(), name);
        }
        return "Tool [" + name + "] not found.";
    }
}