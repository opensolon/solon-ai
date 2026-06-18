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
        if (LOG.isDebugEnabled()) {
            LOG.debug("Action for agent [{}], toolName:{}, args:{}", config.getName(), toolName, args);
        }

        ToolExchanger toolExchanger = new ToolExchanger(toolName, args);

        // 1. 触发前置生命周期
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            if (item.target.isEnabled()) {
                item.target.onAction(trace, toolExchanger);
            }
        }

        // 2. 如果前置拦截器直接挂起或截断了路由，立刻退出（交给 finally 闭环）
        if (trace.getSession().isPending() || Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        // 3. 推送流式动作片
        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(new ActionChunk(trace, toolName, args));
        }

        long startMs = System.currentTimeMillis();
        ToolResult result = null;
        Throwable thrownError = null;

        try {
            // 4. 执行工具调用
            if (Assert.isEmpty(toolExchanger.getResult())) {
                result = executeTool(trace, toolName, args);
            } else {
                result = ToolResult.success(toolExchanger.getResult());
            }

            if (result != null && !trace.getSession().isPending() && !Agent.ID_END.equals(trace.getRoute())) {
                toolExchanger.setResult(result.getContent());
            }

            // 最终返回当前轮次处理后的最新观测值
            return toolExchanger.getResult() != null ? ToolResult.success(toolExchanger.getResult()) : null;

        } catch (Throwable e) {
            thrownError = e;
            throw e;
        } finally {
            // ================== 【100% 强物理闭环】 ==================
            long durationMs = System.currentTimeMillis() - startMs;
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
            } else if (toolExchanger.getResult() != null) {
                if (call == null) {
                    observationMessage = ChatMessage.ofUser("Observation: " + toolExchanger.getResult());
                } else {
                    observationMessage = ChatMessage.ofTool(ToolResult.success(toolExchanger.getResult()), call.getName(), call.getId(), false);
                }
            }

            // 无论正常结束、挂起退出、还是中途抛出 critical error，100% 走统一清理与下发逻辑
            handleSingleObservation(trace, toolExchanger, observationMessage, durationMs, thrownError, toolResults);
        }
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
                        toolResults.add(observationMessage);
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
            toolResults.add(chatMessage);
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
    private void handleSingleObservation(ReActTrace trace, ToolExchanger toolExchanger,
                                         ChatMessage observationMessage, long durationMs,
                                         Throwable error, List<ChatMessage> toolResults) {

        if (observationMessage == null) {
            if (error == null) {
                error = new RuntimeException("The tool task has been interrupted or pending.");
            }
            observationMessage = ChatMessage.ofAssistant("");
        } else if (toolResults != null) {
            toolResults.add(observationMessage);
        }

        // 1. 流式客户端通知闭环
        if (trace.getOptions().getStreamSink() != null) {
            try {
                trace.getOptions().getStreamSink().next(
                        new ObservationChunk(trace, toolExchanger.getToolName(), toolExchanger.getArgs(), observationMessage, error, durationMs));
            } catch (Throwable e) {
                LOG.error("Push ObservationChunk failed", e);
            }
        }

        // 2. 拦截器现场清理闭环
        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                try {
                    entity.target.onObservation(trace, toolExchanger, observationMessage, error, durationMs);
                } catch (Throwable e) {
                    LOG.error("Interceptor onObservation execution failed", e);
                }
            }
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