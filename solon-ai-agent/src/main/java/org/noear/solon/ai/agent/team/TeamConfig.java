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
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;

/**
 * 团队协作智能体（Team Agent）配置类
 * <p>用于定义团队的成员组成、协作协议、运行策略以及决策大脑（Supervisor）的各项参数。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamConfig {
    /**
     * 团队名称
     */
    private String name;
    /**
     * 团队标题（用于可视化显示）
     */
    private String title;
    /**
     * 团队职责描述（用于多层团队协作场景下的角色识别）
     */
    private String description;
    /**
     * 主管（Supervisor）使用的聊天模型，负责决策和调度
     */
    private final ChatModel chatModel;
    /**
     * 主管决策时的聊天配置选项（如控制 temperature 以确保调度逻辑严谨）
     */
    private Consumer<ChatOptions> chatOptions;
    /**
     * 团队成员映射表，LinkedHashMap 保持成员加入顺序
     */
    private final Map<String, Agent> agentMap = new LinkedHashMap<>();
    /**
     * 协作协议，默认为 HIERARCHICAL（层级模式/主管调度模式）
     */
    private TeamProtocol protocol = TeamProtocols.HIERARCHICAL.create(this);
    /**
     * 图结构微调器，允许在协议生成的默认执行图基础上进行自定义链路修改
     */
    private Consumer<GraphSpec> graphAdjuster;
    /**
     * 任务完成标识符，由模型输出此内容代表团队任务整体终结
     */
    private String finishMarker;
    /**
     * 配置输出 key
     */
    private String outputKey;
    /**
     * 团队协作的最大总迭代次数，防止成员间无限“踢皮球”
     */
    private int maxTotalIterations = 8;
    /**
     * 主管决策失败后的最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试延迟时间（毫秒）
     */
    private long retryDelayMs = 1000L;
    /**
     * 生命周期拦截器，可用于监控成员切换和消息流转
     */
    private final List<RankEntity<TeamInterceptor>> interceptorList = new ArrayList<>();
    /**
     * 团队系统提示词模板提供者
     */
    private TeamPromptProvider promptProvider = TeamPromptProviderEn.getInstance();


    /**
     * 核心构造函数
     *
     * @param chatModel 决策大脑模型
     */
    public TeamConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 设置图结构微调逻辑
     */
    public void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) {
        this.graphAdjuster = graphAdjuster;
    }

    /**
     * 配置决策重试策略
     */
    public void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    public void setFinishMarker(String finishMarker) {
        this.finishMarker = finishMarker;
    }

    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    /**
     * 设置全队最大迭代限制
     */
    public void setMaxTotalIterations(int maxTotalIterations) {
        this.maxTotalIterations = Math.max(1, maxTotalIterations);
    }

    public void addInterceptor(TeamInterceptor interceptor) {
        this.addInterceptor(interceptor, 0);
    }

    public void addInterceptor(TeamInterceptor interceptor, int index) {
        this.interceptorList.add(new RankEntity<>(interceptor, index));

        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }
    }

    public void setPromptProvider(TeamPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    /**
     * 设置主管角色的 ChatOptions
     */
    public void setChatOptions(Consumer<ChatOptions> chatOptions) {
        this.chatOptions = chatOptions;
    }

    /**
     * 添加团队成员
     *
     * @param agent 具体的智能体实例（可以是简单的 Agent，也可以是嵌套的 TeamAgent）
     */
    public void addAgent(Agent agent) {
        Objects.requireNonNull(agent.name(), "agent.name is required");
        Objects.requireNonNull(agent.description(), "agent.description is required for collaboration");

        agentMap.put(agent.name(), agent);
    }

    /**
     * 设置协作协议（决定了执行图的拓扑结构）
     */
    public void setProtocol(TeamProtocolFactory protocolFactory) {
        Objects.requireNonNull(protocolFactory, "protocolFactory");
        this.protocol = protocolFactory.create(this);
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public Consumer<ChatOptions> getChatOptions() {
        return chatOptions;
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

    /**
     * 获取任务完成标识
     * <p>如果未配置，则自动生成格式如：[TEAMNAME_FINISH]</p>
     */
    public String getFinishMarker() {
        if (finishMarker == null) {
            finishMarker = "[" + name.toUpperCase() + "_FINISH]";
        }

        return finishMarker;
    }

    public String getOutputKey() {
        return outputKey;
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

    public List<RankEntity<TeamInterceptor>> getInterceptorList() {
        return interceptorList;
    }

    /**
     * 获取团队提示词提供者
     */
    public TeamPromptProvider getPromptProvider() {
        return promptProvider;
    }

    public Locale getLocale() {
        return promptProvider.getLocale();
    }
}