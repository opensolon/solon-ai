package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;

import java.util.ArrayList;
import java.util.List;

public class ReActConfig {
    private ChatModel chatModel;
    private List<FunctionTool> tools = new ArrayList<>();
    private int maxIterations = 10;
    private String systemPromptTemplate;
    private boolean enableLogging = false;
    private float temperature = 0.7F;
    private int maxResponseTokens = 2048;
    private String finishMarker = "[FINISH]";

    public ReActConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public ReActAgent create() {
        return new ReActAgent(this);
    }

    public String getSystemPromptTemplate() {
        if (systemPromptTemplate != null) return systemPromptTemplate;

        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant using ReAct mode.\n");
        sb.append("Format: \nThought: your reasoning\nAction: {\"name\": \"tool_name\", \"arguments\": {}}\nObservation: tool result\n");
        sb.append("When you have the final answer, use: ").append(finishMarker).append(" your answer.\n");
        if (!tools.isEmpty()) {
            sb.append("Tools available: ");
            tools.forEach(t -> sb.append(t.name()).append(": ").append(t.description()).append("; "));
        }
        return sb.toString();
    }

    // Builder 链式调用
    public ReActConfig tools(List<FunctionTool> tools) { this.tools = tools; return this; }
    public ReActConfig addTool(FunctionTool tool) { this.tools.add(tool); return this; }
    public ReActConfig addTool(ToolProvider toolProvider) { this.tools.addAll(toolProvider.getTools()); return this; }
    public ReActConfig enableLogging(boolean val) { this.enableLogging = val; return this; }
    public ReActConfig temperature(float val) { this.temperature = val; return this; }
    public ReActConfig maxIterations(int val) { this.maxIterations = val; return this; }

    // Getters
    public List<FunctionTool> getTools() { return tools; }
    public ChatModel getChatModel() { return chatModel; }
    public int getMaxIterations() { return maxIterations; }
    public float getTemperature() { return temperature; }
    public int getMaxResponseTokens() { return maxResponseTokens; }
    public String getFinishMarker() { return finishMarker; }
    public boolean isEnableLogging() { return enableLogging; }
}