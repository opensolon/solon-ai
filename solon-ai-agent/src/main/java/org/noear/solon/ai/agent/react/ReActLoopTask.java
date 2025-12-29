package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.LogUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 循环任务组件
 */
public class ReActLoopTask implements TaskComponent {
    private final ChatModel chatModel;
    private final List<FunctionTool> tools;
    private final int maxIterations;
    private final String systemPromptTemplate;
    private final boolean enableLogging;
    private final float temperature;
    private final int maxResponseTokens;
    private final float topP;
    private final String finishMarker;

    public ReActLoopTask(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate, boolean enableLogging, float temperature, int maxResponseTokens, float topP, String finishMarker) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.maxIterations = maxIterations;
        this.systemPromptTemplate = systemPromptTemplate;
        this.enableLogging = enableLogging;
        this.temperature = temperature;
        this.maxResponseTokens = maxResponseTokens;
        this.topP = topP;
        this.finishMarker = finishMarker != null ? finishMarker : "[FINISH]";
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String prompt = context.getAs("prompt");
        AtomicInteger currentIteration = context.getAs("current_iteration");
        List<ChatMessage> conversationHistory = context.getAs("conversation_history");
        boolean hasFinalAnswer = context.getOrDefault("has_final_answer", false);

        // 添加用户初始提示
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ChatMessage.ofUser(prompt));
        }

        String lastResponse = "";

        while (!hasFinalAnswer && currentIteration.get() < maxIterations) {
            if (enableLogging) {
                LogUtil.global().info("ReAct iteration: " + (currentIteration.get() + 1) + "/" + maxIterations);
            }

            // 构建系统提示，指导模型使用 ReAct 模式
            String systemPrompt = systemPromptTemplate;

            // 准备消息
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.addAll(conversationHistory);

            // 调用模型
            ChatRequestDesc requestDesc = chatModel.prompt(messages);

            // 设置参数
            requestDesc.options(o -> {
                o.temperature(temperature);
                o.max_tokens(maxResponseTokens);
                o.top_p(topP);
            });

            // 如果有工具，添加到请求中
            if (tools != null && !tools.isEmpty()) {
                requestDesc.options(o -> o.toolsAdd(tools));
            }

            // 获取模型响应
            ChatResponse response;
            try {
                response = requestDesc.call();
            } catch (IOException e) {
                if (enableLogging) {
                    LogUtil.global().error("Error calling chat model: " + e.getMessage(), e);
                }
                throw e;
            }

            String content = response.getContent() != null ? response.getContent() : "";
            lastResponse = content;

            if (enableLogging) {
                LogUtil.global().info("Model response: " + content);
            }

            // 将模型响应添加到对话历史
            conversationHistory.add(ChatMessage.ofAssistant(content));

            // 解析响应，检查是否包含工具调用
            boolean hasToolCall = parseAndExecuteToolCalls(content, conversationHistory);

            // 检查是否达到最终答案
            if (containsFinishMarker(content)) {
                hasFinalAnswer = true;
                context.put("final_answer", extractFinalAnswer(content));
                context.put("has_final_answer", true);
            } else if (!hasToolCall) {
                // 如果没有工具调用且没有结束标记，可能是模型直接给出了答案
                hasFinalAnswer = true;
                context.put("final_answer", content.trim());
                context.put("has_final_answer", true);
            }

            // 增加迭代计数
            currentIteration.incrementAndGet();
        }

        // 如果达到最大迭代次数仍未完成，使用最后的响应作为答案
        if (!hasFinalAnswer) {
            if (enableLogging) {
                LogUtil.global().warn("Maximum iterations reached, using last response as answer");
            }
            context.put("final_answer", extractFinalAnswer(lastResponse));
            context.put("has_final_answer", true);
        }
    }

    /**
     * 检查响应是否包含结束标记
     */
    private boolean containsFinishMarker(String response) {
        return response.contains(finishMarker) || response.contains("<|FINISH|>");
    }

    /**
     * 解析并执行工具调用
     */
    private boolean parseAndExecuteToolCalls(String response, List<ChatMessage> conversationHistory) {
        boolean hasToolCall = false;

        // 查找 Action 部分 - 支持多种格式
        Pattern actionPattern1 = Pattern.compile("Action:\\s*(\\{.*?\\})", Pattern.DOTALL);
        Pattern actionPattern2 = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
        Pattern actionPattern3 = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\".*?\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{.*?\\}\\s*\\}", Pattern.DOTALL);

        // 首先尝试匹配 Action 格式
        Matcher matcher = actionPattern1.matcher(response);
        String actionJson = null;

        if (matcher.find()) {
            actionJson = matcher.group(1).trim();
        } else {
            // 尝试匹配其他格式
            matcher = actionPattern2.matcher(response);
            if (matcher.find()) {
                actionJson = matcher.group(1).trim();
            } else {
                matcher = actionPattern3.matcher(response);
                if (matcher.find()) {
                    actionJson = matcher.group(0).trim(); // 使用整个匹配项
                } else {
                    // 尝试从响应中查找 JSON 格式的工具调用
                    Pattern jsonPattern = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{[^}]*\\}\\s*\\}");
                    matcher = jsonPattern.matcher(response);
                    if (matcher.find()) {
                        actionJson = matcher.group(0).trim();
                    }
                }
            }
        }

        if (actionJson != null) {
            try {
                // 解析工具调用 JSON
                ONode actionNode = ONode.ofJson(actionJson);
                String toolName = actionNode.get("name").getString();
                ONode argumentsNode = actionNode.get("arguments");

                // 查找并执行对应的工具
                String result = executeTool(toolName, argumentsNode);

                // 添加观察结果到对话历史
                String observation = "Observation: " + result.toString();
                conversationHistory.add(ChatMessage.ofUser(observation));
                hasToolCall = true;

                if (enableLogging) {
                    LogUtil.global().info("Tool call executed: " + toolName + " with result: " + result);
                }

            } catch (Throwable e) {
                // 如果解析失败，添加错误信息到对话历史
                String error = "Observation: Error parsing or executing tool call: " + e.getMessage();
                conversationHistory.add(ChatMessage.ofUser(error));
                if (enableLogging) {
                    LogUtil.global().error("Error executing tool call: " + e.getMessage(), e);
                }
            }
        } else {
            // 检查是否包含工具调用的 JSON 格式（例如在响应中直接包含工具调用）
            Pattern toolCallPattern = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{[^}]*\\}\\s*\\}");
            matcher = toolCallPattern.matcher(response);

            while (matcher.find()) {
                String toolCallJson = matcher.group(0).trim();
                try {
                    ONode actionNode = ONode.ofJson(toolCallJson);
                    String toolName = actionNode.get("name").getString();
                    ONode argumentsNode = actionNode.get("arguments");

                    // 查找并执行对应的工具
                    String result = executeTool(toolName, argumentsNode);

                    // 添加观察结果到对话历史
                    String observation = "Observation: " + result.toString();
                    conversationHistory.add(ChatMessage.ofUser(observation));
                    hasToolCall = true;

                    if (enableLogging) {
                        LogUtil.global().info("Tool call executed: " + toolName + " with result: " + result);
                    }

                } catch (Throwable e) {
                    // 如果解析失败，添加错误信息到对话历史
                    String error = "Observation: Error parsing or executing tool call: " + e.getMessage();
                    conversationHistory.add(ChatMessage.ofUser(error));
                    if (enableLogging) {
                        LogUtil.global().error("Error executing tool call: " + e.getMessage(), e);
                    }
                }
            }
        }

        return hasToolCall;
    }

    /**
     * 执行工具调用
     */
    private String executeTool(String toolName, ONode arguments) throws Throwable {
        // 在工具列表中查找匹配的工具
        for (FunctionTool tool : tools) {
            if (tool.name().equals(toolName)) {
                // 解析参数并执行工具
                Map<String, Object> argsMap = arguments.toBean(Map.class);
                String result = tool.handle(argsMap);
                // 确保返回字符串形式的结果
                return result != null ? result.toString() : "Tool execution returned null";
            }
        }

        // 如果找不到工具，抛出异常
        throw new IllegalArgumentException("Tool not found: " + toolName + ". Available tools: " +
                String.join(", ", tools.stream().map(FunctionTool::name).toArray(String[]::new)));
    }

    /**
     * 提取最终答案
     */
    private String extractFinalAnswer(String response) {
        // 查找 finishMarker 或 <|FINISH|> 标记后的答案
        if (response.contains(finishMarker)) {
            int finishIndex = response.indexOf(finishMarker);
            if (finishIndex + finishMarker.length() < response.length()) {
                return response.substring(finishIndex + finishMarker.length()).trim();
            } else {
                // 如果 finishMarker 是最后的内容，返回空字符串或前面的内容
                int lastThoughtIndex = response.lastIndexOf("Thought:");
                if (lastThoughtIndex > -1) {
                    return response.substring(lastThoughtIndex).trim();
                } else {
                    return response.substring(0, finishIndex).trim();
                }
            }
        } else if (response.contains("<|FINISH|>")) {
            int finishIndex = response.indexOf("<|FINISH|>");
            if (finishIndex + 10 < response.length()) {
                return response.substring(finishIndex + 10).trim(); // 10 是 "<|FINISH|>" 的长度
            } else {
                int lastThoughtIndex = response.lastIndexOf("Thought:");
                if (lastThoughtIndex > -1) {
                    return response.substring(lastThoughtIndex).trim();
                } else {
                    return response.substring(0, finishIndex).trim();
                }
            }
        }

        // 如果没有找到结束标记，返回整个响应
        return response.trim();
    }
}