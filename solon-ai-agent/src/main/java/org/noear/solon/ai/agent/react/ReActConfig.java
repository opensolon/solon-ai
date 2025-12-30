package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.intercept.FlowInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ReAct Agent 配置类
 */
public class ReActConfig {
    private ChatModel chatModel;
    private List<FunctionTool> tools = new ArrayList<>();
    private int maxIterations = 10;
    private boolean enableLogging = false;
    private float temperature = 0.7F;
    private int maxTokens = 2048;
    private String finishMarker = "[FINISH]";
    private List<FlowInterceptor> flowInterceptors = new ArrayList<>();
    private ReActPromptProvider promptProvider = new ReActPromptProviderEn();
    private ReActListener listener; // 新增：过程监听

    public ReActConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        this.chatModel = chatModel;
    }

    /**
     * 生成标准 ReAct 提示词
     * 强制模型遵循 Thought/Action/Observation 的链式思考格式
     */
    public String getSystemPromptTemplate() {
        return promptProvider.getSystemPrompt(this);
    }

    // --- Builder 链式调用方法 ---
    public ReActConfig tools(List<FunctionTool> tools) {
        this.tools = tools;
        return this;
    }

    public ReActConfig addTool(FunctionTool tool) {
        this.tools.add(tool);
        return this;
    }

    public ReActConfig addTool(ToolProvider toolProvider) {
        this.tools.addAll(toolProvider.getTools());
        return this;
    }

    public ReActConfig enableLogging(boolean val) {
        this.enableLogging = val;
        return this;
    }

    public ReActConfig temperature(float val) {
        this.temperature = val;
        return this;
    }

    public ReActConfig maxTokens(int val) {
        this.maxTokens = val;
        return this;
    }

    public ReActConfig maxIterations(int val) {
        this.maxIterations = val;
        return this;
    }

    public ReActConfig addFlowInterceptor(FlowInterceptor val) {
        this.flowInterceptors.add(val);
        return this;
    }

    public ReActConfig promptProvider(ReActPromptProvider val) {
        this.promptProvider = val;
        return this;
    }

    public ReActConfig listener(ReActListener val) {
        this.listener = val;
        return this;
    }


    // --- Getters ---
    public List<FunctionTool> getTools() {
        return tools;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public float getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getFinishMarker() {
        return finishMarker;
    }

    public boolean isEnableLogging() {
        return enableLogging;
    }

    public List<FlowInterceptor> getFlowInterceptors() {
        return flowInterceptors;
    }

    public ReActPromptProvider getPromptProvider() {
        return promptProvider;
    }

    public ReActListener getListener() {
        return listener;
    }
}