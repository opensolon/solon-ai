package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.noear.snack4.ONode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct (Reasoning and Acting) Agent 实现
 * 基于 LangGraph 思想，使用 solon-flow 提供的图接口和 solon-ai-core 的 ChatModel
 *
 * @author noear 2025/11/30 created
 */
public class ReActAgent implements Agent {
    private final ChatModel chatModel;
    private final List<FunctionTool> tools;
    private final int maxIterations;
    private final FlowEngine flowEngine;
    private final String systemPromptTemplate;

    public ReActAgent(ChatModel chatModel) {
        this(chatModel, new ArrayList<>(), 10, null);
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools) {
        this(chatModel, tools, 10, null);
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations) {
        this(chatModel, tools, maxIterations, null);
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate) {
        this.chatModel = chatModel;
        this.tools = tools != null ? tools : new ArrayList<>();
        this.maxIterations = maxIterations;
        this.systemPromptTemplate = systemPromptTemplate != null ? systemPromptTemplate : createDefaultSystemPrompt();
        this.flowEngine = FlowEngine.newInstance();
    }

    private String createDefaultSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个使用 ReAct (Reasoning and Acting) 模式的 AI 助手。\n");
        sb.append("你需要按照 Thought/Action/Observation 的循环来解决问题。\n");
        sb.append("格式要求：\n");
        sb.append("- 思考 (Thought): <你的思考过程>\n");
        if (!tools.isEmpty()) {
            sb.append("- 行动 (Action): {\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}\n");
            sb.append("  可用工具: ");
            for (int i = 0; i < tools.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tools.get(i).name()).append(" (").append(tools.get(i).description()).append(")");
            }
            sb.append("\n");
        }
        sb.append("- 观察 (Observation): <工具执行结果>\n");
        sb.append("当问题解决时，以 [FINISH] 或 <|FINISH|> 标记结束并给出最终答案。\n");
        sb.append("严格按照上述格式进行思考、行动和观察。");
        
        return sb.toString();
    }

    @Override
    public String run(String prompt) throws Throwable {
        // 创建图来执行 ReAct 循环
        Graph graph = Graph.create("react_agent", decl -> {
            // 开始节点
            decl.addStart("start")
                    .linkAdd("react_loop");

            // ReAct 循环节点
            decl.addActivity("react_loop")
                    .task(new ReActLoopTask(chatModel, tools, maxIterations, systemPromptTemplate))
                    .linkAdd("end");

            // 结束节点
            decl.addEnd("end");
        });

        // 创建上下文
        FlowContext context = FlowContext.of()
                .put("prompt", prompt)
                .put("max_iterations", maxIterations)
                .put("current_iteration", new AtomicInteger(0))
                .put("conversation_history", new ArrayList<ChatMessage>())
                .put("final_answer", "");

        // 执行图
        flowEngine.eval(graph, context);

        // 返回最终结果
        return context.get("final_answer").toString();
    }

    /**
     * ReAct 循环任务组件
     */
    public static class ReActLoopTask implements TaskComponent {
        private final ChatModel chatModel;
        private final List<FunctionTool> tools;
        private final int maxIterations;
        private final String systemPromptTemplate;

        public ReActLoopTask(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate) {
            this.chatModel = chatModel;
            this.tools = tools;
            this.maxIterations = maxIterations;
            this.systemPromptTemplate = systemPromptTemplate;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            String prompt = context.getAs("prompt");
            AtomicInteger currentIteration = context.getAs("current_iteration");
            List<ChatMessage> conversationHistory = context.getAs("conversation_history");
            
            // 添加用户初始提示
            if (conversationHistory.isEmpty()) {
                conversationHistory.add(ChatMessage.ofUser(prompt));
            }

            // ReAct 循环
            boolean hasFinalAnswer = false;
            String lastResponse = "";
            
            while (!hasFinalAnswer && currentIteration.get() < maxIterations) {
                // 构建系统提示，指导模型使用 ReAct 模式
                String systemPrompt = systemPromptTemplate;

                // 准备消息
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(new SystemMessage(systemPrompt));
                messages.addAll(conversationHistory);

                // 调用模型
                ChatRequestDesc requestDesc = chatModel.prompt(messages);
                
                // 如果有工具，添加到请求中
                if (tools != null && !tools.isEmpty()) {
                    requestDesc.options(o -> o.toolsAdd(tools));
                }

                // 获取模型响应
                ChatResponse response = requestDesc.call();
                String content = response.getContent() != null ? response.getContent() : "";
                lastResponse = content;

                // 将模型响应添加到对话历史
                conversationHistory.add(ChatMessage.ofAssistant(content));

                // 解析响应，检查是否包含工具调用
                boolean hasToolCall = parseAndExecuteToolCalls(content, conversationHistory);

                // 检查是否达到最终答案
                if (containsFinishMarker(content)) {
                    hasFinalAnswer = true;
                    context.put("final_answer", extractFinalAnswer(content));
                } else if (!hasToolCall) {
                    // 如果没有工具调用且没有结束标记，可能是模型直接给出了答案
                    hasFinalAnswer = true;
                    context.put("final_answer", content.trim());
                }

                // 增加迭代计数
                currentIteration.incrementAndGet();
            }

            // 如果达到最大迭代次数仍未完成，使用最后的响应作为答案
            if (!hasFinalAnswer) {
                context.put("final_answer", extractFinalAnswer(lastResponse));
            }
        }

        /**
         * 检查响应是否包含结束标记
         */
        private boolean containsFinishMarker(String response) {
            return response.contains("[FINISH]") || response.contains("<|FINISH|>");
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
                    
                } catch (Throwable e) {
                    // 如果解析失败，添加错误信息到对话历史
                    String error = "Observation: Error parsing or executing tool call: " + e.getMessage();
                    conversationHistory.add(ChatMessage.ofUser(error));
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
                        
                    } catch (Throwable e) {
                        // 如果解析失败，添加错误信息到对话历史
                        String error = "Observation: Error parsing or executing tool call: " + e.getMessage();
                        conversationHistory.add(ChatMessage.ofUser(error));
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
            // 查找 [FINISH] 或 <|FINISH|> 标记后的答案
            if (response.contains("[FINISH]")) {
                int finishIndex = response.indexOf("[FINISH]");
                if (finishIndex + 9 < response.length()) {
                    return response.substring(finishIndex + 9).trim(); // 9 是 "[FINISH]" 的长度
                } else {
                    // 如果 [FINISH] 是最后的内容，返回空字符串或前面的内容
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
}