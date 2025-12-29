package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.LogUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ReActAgent implements Agent {
    private final ChatModel chatModel;
    private final List<FunctionTool> tools;
    private final int maxIterations;
    private final FlowEngine flowEngine;
    private final String systemPromptTemplate;
    private final boolean enableLogging;
    private final float temperature;
    private final int maxResponseTokens;
    private final float topP;
    private final String finishMarker;

    public ReActAgent(ChatModel chatModel) {
        this(chatModel, new ArrayList<>(), 10, null, false, 0.7F, 2048, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools) {
        this(chatModel, tools, 10, null, false, 0.7F, 2048, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations) {
        this(chatModel, tools, maxIterations, null, false, 0.7F, 2048, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate) {
        this(chatModel, tools, maxIterations, systemPromptTemplate, false, 0.7F, 2048, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate, boolean enableLogging) {
        this(chatModel, tools, maxIterations, systemPromptTemplate, enableLogging, 0.7F, 2048, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate, boolean enableLogging, float temperature) {
        this(chatModel, tools, maxIterations, systemPromptTemplate, enableLogging, temperature, 2048, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate, boolean enableLogging, float temperature, int maxResponseTokens) {
        this(chatModel, tools, maxIterations, systemPromptTemplate, enableLogging, temperature, maxResponseTokens, 0.9F, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate, boolean enableLogging, float temperature, int maxResponseTokens, float topP) {
        this(chatModel, tools, maxIterations, systemPromptTemplate, enableLogging, temperature, maxResponseTokens, topP, "[FINISH]");
    }

    public ReActAgent(ChatModel chatModel, List<FunctionTool> tools, int maxIterations, String systemPromptTemplate, boolean enableLogging, float temperature, int maxResponseTokens, float topP, String finishMarker) {
        this.chatModel = chatModel;
        this.tools = tools != null ? tools : new ArrayList<>();
        this.maxIterations = maxIterations;
        this.systemPromptTemplate = systemPromptTemplate != null ? systemPromptTemplate : createDefaultSystemPrompt();
        this.enableLogging = enableLogging;
        this.temperature = Math.max(0.0F, Math.min(1.0F, temperature)); // 限制温度在0-1之间
        this.maxResponseTokens = Math.max(1, maxResponseTokens); // 最小值为1
        this.topP = Math.max(0.0F, Math.min(1.0F, topP)); // 限制topP在0-1之间
        this.finishMarker = finishMarker != null ? finishMarker : "[FINISH]";
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
        sb.append("当问题解决时，以 ").append(finishMarker).append(" 标记结束并给出最终答案。\n");
        sb.append("严格按照上述格式进行思考、行动和观察。");
        
        return sb.toString();
    }

    @Override
    public String run(String prompt) throws Throwable {
        if (enableLogging) {
            LogUtil.global().info("Starting ReActAgent with prompt: " + prompt);
        }
        
        // 创建图来执行 ReAct 循环
        Graph graph = Graph.create("react_agent", decl -> {
            // 开始节点
            decl.addStart("start")
                    .linkAdd("react_loop");

            // ReAct 循环节点
            decl.addActivity("react_loop")
                    .task(new ReActLoopTask(chatModel, tools, maxIterations, systemPromptTemplate, enableLogging, temperature, maxResponseTokens, topP, finishMarker))
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
                .put("final_answer", "")
                .put("has_final_answer", false);

        // 执行图
        flowEngine.eval(graph, context);

        // 返回最终结果
        String result = context.get("final_answer").toString();
        if (enableLogging) {
            LogUtil.global().info("ReActAgent finished with result: " + result);
        }
        return result;
    }
}