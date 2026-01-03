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
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Team 配置
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamConfig {
    private String name;
    private String description;
    private final ChatModel chatModel;
    private final Map<String, Agent> agentMap = new LinkedHashMap<>();
    private TeamProtocol protocol = TeamProtocols.HIERARCHICAL;
    private Consumer<GraphSpec> graphAdjuster;
    private String finishMarker;
    private int maxTotalIterations = 8;
    private int maxRetries = 3;
    private long retryDelayMs = 1000L;
    private TeamInterceptor interceptor;
    private TeamPromptProvider promptProvider = TeamPromptProviderEn.getInstance();
    private Consumer<ChatOptions> supervisorOptions;

    public TeamConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) {
        this.graphAdjuster = graphAdjuster;
    }

    public void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    public void setFinishMarker(String finishMarker) {
        this.finishMarker = finishMarker;
    }

    public void setMaxTotalIterations(int maxTotalIterations) {
        this.maxTotalIterations = Math.max(1, maxTotalIterations);
    }

    public void setInterceptor(TeamInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    public void setPromptProvider(TeamPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    public void setSupervisorOptions(Consumer<ChatOptions> supervisorOptions) {
        this.supervisorOptions = supervisorOptions;
    }

    public void addAgent(Agent agent) {
        Objects.requireNonNull(agent.name(), "agent.name");
        Objects.requireNonNull(agent.description(), "agent.description");

        agentMap.put(agent.name(), agent);
    }

    public void setProtocol(TeamProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol");
        this.protocol = protocol;
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

    public TeamProtocol getProtocol() {
        return protocol;
    }

    public Consumer<GraphSpec> getGraphAdjuster() {
        return graphAdjuster;
    }


    public String getFinishMarker() {
        if (finishMarker == null) {
            finishMarker = "[" + name.toUpperCase() + "_FINISH]";
        }

        return finishMarker;
    }

    public int getMaxTotalIterations() {
        return maxTotalIterations;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public TeamInterceptor getInterceptor() {
        return interceptor;
    }

    public String getSystemPrompt(TeamTrace trace) {
        return promptProvider.getSystemPrompt(trace);
    }

    public Consumer<ChatOptions> getSupervisorOptions() {
        return supervisorOptions;
    }
}