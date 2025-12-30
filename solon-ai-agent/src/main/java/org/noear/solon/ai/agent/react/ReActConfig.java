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

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ReAct 配置类
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActConfig {
    private String name;
    private ChatModel chatModel;
    private List<FunctionTool> tools = new ArrayList<>();
    private int maxSteps = 10;
    private boolean enableLogging = false;
    private float temperature = 0.7F;
    private int maxTokens = 2048;
    private String finishMarker = "[FINISH]";
    private ReActInterceptor interceptor;
    private ReActPromptProvider promptProvider = new ReActPromptProviderEn();

    public ReActConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");

        this.chatModel = chatModel;
    }

    /**
     * 生成标准 ReAct 提示词
     * 强制模型遵循 Thought/Action/Observation 的链式思考格式
     */
    public String getSystemPrompt() {
        return promptProvider.getSystemPrompt(this);
    }

    // --- Builder 链式调用方法 ---

    public ReActConfig name(String name) {
        this.name = name;
        return this;
    }

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

    public ReActConfig maxSteps(int val) {
        this.maxSteps = val;
        return this;
    }

    public ReActConfig interceptor(ReActInterceptor val) {
        this.interceptor = val;
        return this;
    }

    public ReActConfig promptProvider(ReActPromptProvider val) {
        this.promptProvider = val;
        return this;
    }


    // --- Getters ---


    public String getName() {
        return name;
    }

    public List<FunctionTool> getTools() {
        return tools;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public int getMaxSteps() {
        return maxSteps;
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

    public ReActInterceptor getInterceptor() {
        return interceptor;
    }

    public ReActPromptProvider getPromptProvider() {
        return promptProvider;
    }
}