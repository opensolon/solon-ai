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
    private String description;
    private final ChatModel chatModel;
    private final List<FunctionTool> tools = new ArrayList<>();
    private int maxSteps = 10;
    private boolean enableLogging = false;
    private float temperature = 0.7F;
    private int maxTokens = 2048;
    private String finishMarker = "[FINISH]";
    private ReActInterceptor interceptor;
    private ReActPromptProvider promptProvider = ReActPromptProviderEn.getInstance();

    public ReActConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");

        this.chatModel = chatModel;
    }


    // --- Setter  ---

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void addTool(FunctionTool tool) {
        this.tools.add(tool);
    }

    public void addTool(List<FunctionTool> tools) {
        this.tools.addAll(tools);
    }

    public void addTool(ToolProvider toolProvider) {
        this.tools.addAll(toolProvider.getTools());
    }

    public void enableLogging(boolean val) {
        this.enableLogging = val;
    }

    public void setTemperature(float val) {
        this.temperature = val;
    }

    public void setMaxTokens(int val) {
        this.maxTokens = val;
    }

    public void setFinishMarker(String val) {
        this.finishMarker = val;
    }

    public void setMaxSteps(int val) {
        this.maxSteps = val;
    }

    public void setInterceptor(ReActInterceptor val) {
        this.interceptor = val;
    }

    public void setPromptProvider(ReActPromptProvider val) {
        this.promptProvider = val;
    }


    // --- Getters ---


    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
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

    public String getSystemPrompt() {
        return promptProvider.getSystemPrompt(this);
    }
}