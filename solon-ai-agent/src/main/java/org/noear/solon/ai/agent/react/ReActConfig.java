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

import java.util.*;

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
    private final Map<String, FunctionTool> toolMap = new LinkedHashMap<>();
    private int maxSteps = 10;
    private float temperature = 0.7F;
    private int maxRetries = 3;
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
        this.toolMap.put(tool.name(), tool);
    }

    public void addTool(Collection<FunctionTool> tools) {
        for (FunctionTool tool : tools) {
            addTool(tool);
        }
    }

    public void addTool(ToolProvider toolProvider) {
        addTool(toolProvider.getTools());
    }

    public void setTemperature(float val) {
        this.temperature = val;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
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

    public Map<String, FunctionTool> getTools() {
        return toolMap;
    }

    public FunctionTool getTool(String name) {
        return toolMap.get(name);
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

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getFinishMarker() {
        return finishMarker;
    }

    public ReActInterceptor getInterceptor() {
        return interceptor;
    }

    public String getSystemPrompt() {
        return promptProvider.getSystemPrompt(this);
    }
}