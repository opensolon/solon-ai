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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.GraphSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 团队配置
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamConfig {
    private String name;
    private String description;
    private final ChatModel chatModel;
    private final Map<String, Agent> agentMap = new LinkedHashMap<>();
    private TeamStrategy strategy;
    private Consumer<GraphSpec> graphBuilder;
    private String finishMarker = "[FINISH]";
    private int maxTotalIterations = 8;
    private TeamPromptProvider promptProvider = TeamPromptProviderEn.getInstance();

    public TeamConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setGraphBuilder(Consumer<GraphSpec> graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    public void setFinishMarker(String finishMarker) {
        this.finishMarker = finishMarker;
    }

    public void setMaxTotalIterations(int maxTotalIterations) {
        this.maxTotalIterations = Math.max(1, maxTotalIterations);
    }

    public void setPromptProvider(TeamPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    public void addAgent(Agent agent) {
        Objects.requireNonNull(agent.name(), "agent.name");
        Objects.requireNonNull(agent.description(), "agent.description");

        agentMap.put(agent.name(), agent);
    }

    public void setStrategy(TeamStrategy strategy) {
        this.strategy = strategy;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public Map<String, Agent> getAgentMap() {
        return agentMap;
    }

    public TeamStrategy getStrategy() {
        return strategy;
    }

    public Consumer<GraphSpec> getGraphBuilder() {
        return graphBuilder;
    }

    public String getFinishMarker() {
        return finishMarker;
    }

    public int getMaxTotalIterations() {
        return maxTotalIterations;
    }

    public String getSystemPrompt(Prompt prompt) {
        return promptProvider.getSystemPrompt(this, prompt);
    }
}