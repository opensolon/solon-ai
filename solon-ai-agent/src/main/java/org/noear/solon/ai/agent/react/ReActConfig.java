package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.util.List;

/**
 * ReActAgent 配置类
 */
public class ReActConfig {
    private ChatModel chatModel;
    private List<FunctionTool> tools;
    private int maxIterations;
    private String systemPromptTemplate;
    private boolean enableLogging;
    private float temperature;
    private int maxResponseTokens;
    private float topP;
    private String finishMarker;

    public ReActConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.tools = null;
        this.maxIterations = 10;
        this.systemPromptTemplate = null;
        this.enableLogging = false;
        this.temperature = 0.7F;
        this.maxResponseTokens = 2048;
        this.topP = 0.9F;
        this.finishMarker = "[FINISH]";
    }

    public ReActConfig tools(List<FunctionTool> tools) {
        this.tools = tools;
        return this;
    }

    public ReActConfig maxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
        return this;
    }

    public ReActConfig systemPromptTemplate(String systemPromptTemplate) {
        this.systemPromptTemplate = systemPromptTemplate;
        return this;
    }

    public ReActConfig enableLogging(boolean enableLogging) {
        this.enableLogging = enableLogging;
        return this;
    }

    public ReActConfig temperature(float temperature) {
        this.temperature = Math.max(0.0F, Math.min(1.0F, temperature)); // 限制温度在0-1之间
        return this;
    }

    public ReActConfig maxResponseTokens(int maxResponseTokens) {
        this.maxResponseTokens = Math.max(1, maxResponseTokens); // 最小值为1
        return this;
    }

    public ReActConfig topP(float topP) {
        this.topP = Math.max(0.0F, Math.min(1.0F, topP)); // 限制topP在0-1之间
        return this;
    }

    public ReActConfig finishMarker(String finishMarker) {
        this.finishMarker = finishMarker;
        return this;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public List<FunctionTool> getTools() {
        return tools;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public String getSystemPromptTemplate() {
        return systemPromptTemplate;
    }

    public boolean isEnableLogging() {
        return enableLogging;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxResponseTokens() {
        return maxResponseTokens;
    }

    public double getTopP() {
        return topP;
    }

    public String getFinishMarker() {
        return finishMarker;
    }

    public ReActAgent build() {
        return new ReActAgent(
                chatModel,
                tools,
                maxIterations,
                systemPromptTemplate,
                enableLogging,
                temperature,
                maxResponseTokens,
                topP,
                finishMarker
        );
    }
}