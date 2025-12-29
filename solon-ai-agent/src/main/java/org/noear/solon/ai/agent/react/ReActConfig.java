package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
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

    // ... Getters and Setters (省略重复的 Boilerplate) ...

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

    // 各种 Builder 方法...
    public ReActConfig tools(List<FunctionTool> tools) { this.tools = tools; return this; }
    public ReActConfig enableLogging(boolean val) { this.enableLogging = val; return this; }
    public List<FunctionTool> getTools() { return tools; }
    public ChatModel getChatModel() { return chatModel; }
    public int getMaxIterations() { return maxIterations; }
    public float getTemperature() { return temperature; }
    public int getMaxResponseTokens() { return maxResponseTokens; }
    public String getFinishMarker() { return finishMarker; }
    public boolean isEnableLogging() { return enableLogging; }
}