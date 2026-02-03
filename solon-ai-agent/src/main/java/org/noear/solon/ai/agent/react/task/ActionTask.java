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

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] action starting (Step: {})...", config.getName(), trace.getStepCount());
        }

        AssistantMessage lastAssistant = trace.getLastReasonMessage();

        // 1. 优先处理原生工具调用（Native Tool Calls）
        if (lastAssistant != null && Assert.isNotEmpty(lastAssistant.getToolCalls())) {
            for (ToolCall call : lastAssistant.getToolCalls()) {
                processNativeToolCall(node, trace, call);
                if (Agent.ID_END.equals(trace.getRoute())) {
                    break;
                }
            }
            return;
        }

        // 2. 文本模式：解析模型输出中的 Action 块
        processTextModeAction(node, trace);
    }


    private String doAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        trace.setLastObservation(null);
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onAction(trace, toolName, args);
        }

        if (trace.isInterrupted()) {
            return null;
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            return null;
        }

        // 4. 执行工具
        final String result;
        if(Assert.isEmpty(trace.getLastObservation())){
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

        if (trace.isInterrupted()) {
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
    private void processNativeToolCall(Node node, ReActTrace trace, ToolCall call) throws Throwable {
        if(LOG.isDebugEnabled()) {
            LOG.debug("Processing native tool call for agent [{}]: {}.", config.getName(), call);
        }

        Map<String, Object> args = (call.arguments() == null) ? new HashMap<>() : call.arguments();

        // 触发 Action 生命周期拦截
        String result = doAction(trace, call.name(), args);
        if(result == null){
            return;
        }

        // 协议闭环：回填 ToolMessage
        ToolMessage toolMessage = ChatMessage.ofTool(result, call.name(), call.id());
        trace.getWorkingMemory().addMessage(trace.getLastReasonMessage());
        trace.getWorkingMemory().addMessage(toolMessage);

        if(trace.getOptions().getStreamSink() != null){
            trace.getOptions().getStreamSink().next(
                    new ActionChunk(node, trace, toolMessage));
        }
    }

    /**
     * 解析并执行文本模式下的 Action 指令
     */
    private void processTextModeAction(Node node, ReActTrace trace) throws Throwable {
        if (trace.getLastReasonMessage() == null) {
            return;
        }

        String lastContent = trace.getLastReasonMessage().getResultContent();
        if (Assert.isEmpty(lastContent)) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing text mode action for agent [{}].", config.getName());
        }

        int actionLabelIndex = lastContent.indexOf("Action:");
        StringBuilder allObservations = new StringBuilder();
        boolean foundAny = false;

        if (actionLabelIndex >= 0) {
            // 从 "Action:" 之后寻找第一个 '{'
            int jsonStart = lastContent.indexOf('{', actionLabelIndex + 7);

            if (jsonStart >= 0) {
                // Action: {'name': 'getUserInfo', 'arguments': {}}

                // 2. 使用 JsonReader 进行流式解析
                // 无需lastIndexOf('}')，streamRead 会自动处理闭合
                StringReader sr = new StringReader(lastContent.substring(jsonStart));
                JsonReader jsonReader = new JsonReader(sr);

                while (true) {
                    try {
                        // 1. 提取 JSON 字符串
                        ONode actionNode = jsonReader.streamRead();
                        if (actionNode == null || actionNode.isObject() == false) {
                            break;
                        }

                        foundAny = true;

                        // 2. 提取纯净的工具名和参数
                        String toolName = actionNode.get("name").getString();
                        ONode argsNode = actionNode.get("arguments");
                        Map<String, Object> args = argsNode.isObject() ? argsNode.toBean(Map.class) : new HashMap<>();

                        // 3. 触发 Action 拦截 (内容是纯的)
                        String result = doAction(trace, toolName, args);

                        if (result != null) {
                            // 6. 拼装回传给 LLM 的协议文本
                            if (allObservations.length() > 0) {
                                allObservations.append("\n");
                            }
                            allObservations.append("Observation: ").append(result);
                        }
                    } catch (Exception e) {
                        allObservations.append("\nObservation: Error parsing Action JSON: ").append(e.getMessage());
                    }
                }
            } else {
                // Action: getUserInfo
                String toolName = lastContent.substring(actionLabelIndex + 7).trim();
                if (trace.getOptions().getTool(toolName) != null) {
                    foundAny = true;

                    // 3. 触发 Action 拦截 (内容是纯的)
                    String result = doAction(trace, toolName, new HashMap<>());

                    if (result != null) {
                        // 6. 拼装回传给 LLM 的协议文本
                        if (allObservations.length() > 0) {
                            allObservations.append("\n");
                        }
                        allObservations.append("Observation: ").append(result);
                    }
                }
            }
        }

        if (foundAny == false) {
            // 容错处理：当模型格式错误时，引导其修正
            if (LOG.isTraceEnabled()) {
                LOG.trace("No valid Action format found in assistant response for agent [{}].", config.getName());
            }

            allObservations.append("Observation: No valid Action format detected. Use JSON: {\"name\": \"...\", \"arguments\": {}}");
        }

        //可能因为拦截原因，会没有产生数据
        if (allObservations.length() > 0) {
            ChatMessage chatMessage = ChatMessage.ofUser(allObservations.toString().trim());
            trace.getWorkingMemory().addMessage(trace.getLastReasonMessage());
            trace.getWorkingMemory().addMessage(chatMessage);

            if (trace.getOptions().getStreamSink() != null) {
                trace.getOptions().getStreamSink().next(
                        new ActionChunk(node, trace, chatMessage));
            }
        }
    }

    /**
     * 查找并执行工具
     * @return 工具输出的字符串结果
     */
    private String executeTool(ReActTrace trace, String name, Map<String, Object> args) {
        if (FeedbackTool.TOOL_NAME.equals(name)) {
            String reason = (String) args.get("reason");
            trace.setFinalAnswer(reason);
            trace.setRoute(Agent.ID_END);
            trace.interrupt(reason);
            return reason;
        }


        FunctionTool tool = trace.getOptions().getTool(name);

        // 如果配置中没有，尝试从协议工具集查找（如 __transfer_to__）
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
                final String result;
                if (trace.getOptions().getInterceptors().isEmpty()) {
                    result = tool.handle(toolReq.getArgs());
                } else {
                    result = new ToolChain(trace.getOptions().getInterceptors(), tool).doIntercept(toolReq);
                }
                trace.incrementToolCallCount();


                return result;
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