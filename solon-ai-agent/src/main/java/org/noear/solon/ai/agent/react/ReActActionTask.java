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
package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作执行（工具执行）任务
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActActionTask implements TaskComponent {
    private final static Logger LOG = LoggerFactory.getLogger(ReActActionTask.class);

    private final ReActConfig config;
    // 正则提取 Action: 后面的 JSON 对象
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(?:```json)?\\s*(\\{.*?\\})\\s*(?:```)?",
            Pattern.DOTALL
    );

    public ReActActionTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);
        ChatMessage lastMessage = trace.getLastMessage();

        // --- 1. 处理 Native Tool Calls (遵循 OpenAI/Solon AI 消息对齐协议) ---
        if (lastMessage instanceof AssistantMessage) {
            AssistantMessage lastAssistant = (AssistantMessage) lastMessage;
            if (Assert.isNotEmpty(lastAssistant.getToolCalls())) {
                for (ToolCall call : lastAssistant.getToolCalls()) {
                    if (config.getInterceptor() != null) {
                        config.getInterceptor().onAction(trace, call.name(), call.arguments());
                    }

                    Map<String, Object> args = call.arguments();
                    if (args == null) args = Collections.emptyMap();

                    String result = executeTool(call.name(), args);

                    if (config.getInterceptor() != null) {
                        config.getInterceptor().onObservation(trace, result);
                    }

                    trace.appendMessage(ChatMessage.ofTool(result, call.name(), call.id()));
                }

                return;
            }
        }

        // --- 2. 处理文本 ReAct 模式 (Observation 模拟) ---
        String lastContent = trace.getLastResponse();
        if (lastContent == null) return;

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

                if (config.getInterceptor() != null) {
                    config.getInterceptor().onAction(trace, toolName, args);
                }

                String result = executeTool(toolName, args);

                if (config.getInterceptor() != null) {
                    config.getInterceptor().onObservation(trace, result);
                }

                allObservations.append("\nObservation: ").append(result);
            } catch (Exception e) {
                allObservations.append("\nObservation: Error parsing Action JSON: ").append(e.getMessage());
            }
        }

        if (foundAny) {
            // 文本模式通过 User 角色模拟系统反馈
            trace.appendMessage(ChatMessage.ofUser(allObservations.toString().trim()));
        } else {
            trace.appendMessage(ChatMessage.ofUser("Observation: No valid Action detected. If you have enough info, please provide Final Answer."));
        }
    }

    private String executeTool(String name, Map<String, Object> args) {
        FunctionTool tool = config.getTool(name);

        if (tool != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Executing tool: {} with args: {}", name, args);
                }

                return tool.handle(args);
            } catch (Throwable e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Error executing tool: " + name, e);
                }

                return "Error executing tool [" + name + "]: " + e.getMessage();
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("Tool not found: {}", name);
        }

        return "Tool [" + name + "] not found.";
    }
}