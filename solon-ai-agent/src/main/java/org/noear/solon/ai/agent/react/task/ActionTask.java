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
import org.noear.solon.ai.agent.react.ReActConfig;
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
 * ReAct 动作执行任务 (Action/Acting Task)
 * <p>
 * 核心职责：
 * 1. 解析上一步 ReasonTask 产生的指令（Action）。
 * 2. 匹配并执行对应的业务工具 (FunctionTool)。
 * 3. 收集执行结果并作为“观察到的信息 (Observation)”反馈回对话上下文。
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ActionTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ActionTask.class);

    // 正则说明：匹配 Action: 后随的 JSON 内容。支持 Markdown json 块包装，支持跨行。
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(?:```json)?\\s*(\\{[\\s\\S]*\\})\\s*(?:```)?",
            Pattern.DOTALL
    );

    private final ReActConfig config;

    public ActionTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return Agent.ID_ACTION;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] action starting...", config.getName());
        }

        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);
        AssistantMessage lastAssistant = trace.getLastAssistantMessage();

        // --- 策略 A: 处理原生工具调用 (Native Tool Calls) ---
        if (lastAssistant != null && Assert.isNotEmpty(lastAssistant.getToolCalls())) {
            for (ToolCall call : lastAssistant.getToolCalls()) {
                processNativeToolCall(trace, call);
            }
            return;
        }

        // --- 策略 B: 处理文本 ReAct 格式 (ReAct Text Mode) ---
        processTextModeAction(trace);
    }

    /**
     * 处理原生协议工具调用
     */
    private void processNativeToolCall(ReActTrace trace, ToolCall call) throws Throwable {
        // 生命周期拦截：工具执行前
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onAction(trace, call.name(), call.arguments());
        }

        Map<String, Object> args = (call.arguments() == null) ? Collections.emptyMap() : call.arguments();

        // 执行工具并捕获异常反馈
        String result = executeTool(trace, call.name(), args);

        // 生命周期拦截：工具执行之后
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onObservation(trace, result);
        }

        // 将 Observation 反馈给模型，作为逻辑闭环
        trace.appendMessage(ChatMessage.ofTool(result, call.name(), call.id()));
    }

    /**
     * 处理文本模式下的 Action 解析与执行
     */
    private void processTextModeAction(ReActTrace trace) throws Throwable {
        // 适用于不支持 ToolCall 协议但能遵循 ReAct 提示词规范的小模型或特定场景
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

                // 执行并汇总观测结果
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
            // 文本模式反馈：将 Observation 作为下一轮 User 输入引导模型继续推理
            trace.appendMessage(ChatMessage.ofUser(allObservations.toString().trim()));
        } else {
            // 模型声明了 Action 但内容非法时的防御引导
            trace.appendMessage(ChatMessage.ofUser("Observation: No valid Action format detected. Use JSON: {\"name\": \"...\", \"arguments\": {}}"));
        }
    }

    /**
     * 执行具体工具
     *
     * @param name 工具名称（对应 FunctionTool 的 name）
     * @param args 参数映射
     * @return 工具执行后的字符串结果（用于反馈给模型）
     */
    private String executeTool(ReActTrace trace, String name, Map<String, Object> args) {
        FunctionTool tool = config.getTool(name);

        if (tool == null) {
            tool = trace.getProtocolTool(name);
        }

        if (tool != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Executing tool: {} with args: {}", name, args);
                }

                //作为扩展上下文（让工具内可以获取 trace）
                args = new LinkedHashMap<>(args);
                args.put("__" + trace.getAgentName(), trace);

                // 执行具体的 Handler 逻辑
                if (trace.getOptions().getInterceptors().isEmpty()) {
                    //没拦截器
                    return tool.handle(args);
                } else {
                    //有拦截器
                    ToolRequest toolReq = new ToolRequest(null, trace.getOptions().getToolsContext(), args);
                    return new ToolChain(trace.getOptions().getInterceptors(), tool).doIntercept(toolReq);
                }

            } catch (IllegalArgumentException e) {
                //参数校验异常，喂给模型进行自愈修复
                return "Invalid arguments for [" + name + "]. " +
                        "Expected Schema: " + tool.inputSchema() + ". " +
                        "Error: " + e.getMessage();
            } catch (Throwable e) {
                LOG.error("Error executing tool: " + name, e);
                // 返回异常信息给模型，模型通常能识别错误并尝试修复参数后重试
                return "Execution error in tool [" + name + "]: " + e.getMessage();
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("Tool not found: {}", name);
        }

        return "Tool [" + name + "] not found.";
    }
}