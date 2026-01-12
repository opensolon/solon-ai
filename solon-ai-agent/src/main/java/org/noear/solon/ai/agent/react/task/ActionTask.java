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
import org.noear.solon.ai.agent.Agent;
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
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 匹配文本模式下的 Action: {JSON} 内容 */
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(?:```json)?\\s*(\\{[\\s\\S]*\\})\\s*(?:```)?",
            Pattern.DOTALL
    );

    private final ReActAgentConfig config;

    public ActionTask(ReActAgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return Agent.ID_ACTION;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] action starting (Step: {})...", config.getName(), trace.getStepCount());
        }

        AssistantMessage lastAssistant = trace.getLastAssistantMessage();

        // 优先处理 Native Tool Calls 协议
        if (lastAssistant != null && Assert.isNotEmpty(lastAssistant.getToolCalls())) {
            for (ToolCall call : lastAssistant.getToolCalls()) {
                processNativeToolCall(trace, call);
            }
            return;
        }

        // 回退处理文本解析模式 (用于小模型)
        processTextModeAction(trace);
    }

    /**
     * 处理标准 ToolCall 协议调用
     */
    private void processNativeToolCall(ReActTrace trace, ToolCall call) throws Throwable {
        // 触发 Action 生命周期拦截
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onAction(trace, call.name(), call.arguments());
        }

        Map<String, Object> args = (call.arguments() == null) ? Collections.emptyMap() : call.arguments();
        String result = executeTool(trace, call.name(), args);

        // 触发 Observation 生命周期拦截
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onObservation(trace, result);
        }

        // 协议闭环：回填 ToolMessage
        trace.appendMessage(ChatMessage.ofTool(result, call.name(), call.id()));
    }

    /**
     * 解析并执行文本模式下的 Action 指令
     */
    private void processTextModeAction(ReActTrace trace) throws Throwable {
        String lastContent = trace.getLastAnswer();
        if (Assert.isEmpty(lastContent)) {
            return;
        }

        Matcher matcher = ACTION_PATTERN.matcher(lastContent);
        StringBuilder allObservations = new StringBuilder();
        boolean foundAny = false;

        while (matcher.find()) {
            foundAny = true;
            try {
                ONode action = ONode.ofJson(matcher.group(1).trim());
                String toolName = action.get("name").getString();
                ONode argsNode = action.get("arguments");
                Map<String, Object> args = argsNode.isObject() ? argsNode.toBean(Map.class) : Collections.emptyMap();

                for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
                    item.target.onAction(trace, toolName, args);
                }

                String result = executeTool(trace, toolName, args);

                for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
                    item.target.onObservation(trace, result);
                }

                allObservations.append("\nObservation: ").append(result);
            } catch (Exception e) {
                allObservations.append("\nObservation: Error parsing Action JSON: ").append(e.getMessage());
            }
        }

        if (foundAny) {
            // 文本模式：将观测结果作为 User 消息反馈给 LLM
            trace.appendMessage(ChatMessage.ofUser(allObservations.toString().trim()));
        } else {
            // 容错处理：当模型格式错误时，引导其修正
            if (LOG.isTraceEnabled()) {
                LOG.trace("No valid Action format found in assistant response for agent [{}].", config.getName());
            }
            trace.appendMessage(ChatMessage.ofUser("Observation: No valid Action format detected. Use JSON: {\"name\": \"...\", \"arguments\": {}}"));
        }
    }

    /**
     * 查找并执行工具
     * @return 工具输出的字符串结果
     */
    private String executeTool(ReActTrace trace, String name, Map<String, Object> args) {
        FunctionTool tool = config.getTool(name);

        // 如果配置中没有，尝试从协议工具集查找（如 __transfer_to__）
        if (tool == null) {
            tool = trace.getProtocolTool(name);
        }

        if (tool != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent [{}] invoking tool [{}], args: {}", config.getName(), name, args);
                }

                // 注入 trace 对象到参数，允许工具内部访问智能体上下文
                args = new LinkedHashMap<>(args);
                args.put("__" + trace.getAgentName(), trace);

                if (trace.getOptions().getInterceptors().isEmpty()) {
                    return tool.handle(args);
                } else {
                    ToolRequest toolReq = new ToolRequest(null, trace.getOptions().getToolsContext(), args);
                    return new ToolChain(trace.getOptions().getInterceptors(), tool).doIntercept(toolReq);
                }
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