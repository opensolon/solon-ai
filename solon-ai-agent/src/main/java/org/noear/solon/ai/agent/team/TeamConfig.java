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
 * 团队协作配置（Team Configuration）
 * <p>用于定义 AI 团队的组织架构、决策大脑（Supervisor）、协作协议以及运行时的治理策略。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamConfig {
    /**
     * 团队唯一标识名称（用于日志追踪及 context 引用）
     */
    private String name;

    /**
     * 团队状态跟踪键（用于在全局上下文中标识该团队的会话数据）
     */
    private volatile String traceKey;

    /**
     * 团队显示标题（用于 UI 展示或报告输出）
     */
    private String title;

    /**
     * 团队核心职能描述（在嵌套团队场景下，供上层主管识别该团队的专业领域）
     */
    private String description;

    /**
     * 调度中心模型（Supervisor Model），负责解析任务、选择专家及审核产出
     */
    private final ChatModel chatModel;

    /**
     * 调度中心推理配置（用于精细化控制 Supervisor 的采样随机性、Token 限制等）
     */
    private Consumer<ChatOptions> chatOptions;

    /**
     * 团队成员名录，存储所有参与协作的专家智能体（Agent）实例
     */
    private final Map<String, Agent> agentMap = new LinkedHashMap<>();

    /**
     * 协作拓扑协议（决定了任务在团队成员间的流转逻辑，如层级式、流水线式等）
     */
    private volatile TeamProtocol protocol = TeamProtocols.HIERARCHICAL.create(this);

    /**
     * 编排图结构微调钩子（允许在协议生成的标准拓扑上，增加自定义的业务逻辑节点或连线）
     */
    private Consumer<GraphSpec> graphAdjuster;

    /**
     * 任务终结符（当 Supervisor 输出此标记时，视为整个协作流程圆满结束）
     */
    private String finishMarker;

    /**
     * 指定最终结果输出到 Context 中的 Key 名
     */
    private String outputKey;

    /**
     * 协作深度熔断阈值（限制一次任务的最大流转轮数，防止由于模型幻觉导致的无限死循环）
     */
    private int maxTotalIterations = 8;

    /**
     * 调度重试限额（当 Supervisor 输出无法解析的指令或发生网络抖动时的重试次数）
     */
    private int maxRetries = 3;

    /**
     * 调度失败后的重试规避延迟时间（毫秒）
     */
    private long retryDelayMs = 1000L;

    /**
     * 协作拦截器链路（用于在 Agent 切换、指令分发前后注入监控、脱敏或审计逻辑）
     */
    private final List<RankEntity<TeamInterceptor>> interceptorList = new ArrayList<>();

    /**
     * 系统提示词（System Prompt）模板提供者，支持多语言动态适配
     */
    private TeamSystemPrompt promptProvider = TeamSystemPromptEn.getInstance();

    /**
     * 基于指定的推理模型初始化团队配置
     *
     * @param chatModel 担任“主管”角色的 ChatModel
     */
    public TeamConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // --- 配置 Setter ---

    /**
     * 设置团队唯一标识名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 设置团队可视化标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 设置团队职能描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 注入自定义的流程图调整逻辑
     */
    public void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) {
        this.graphAdjuster = graphAdjuster;
    }

    /**
     * 统一配置异常调度时的重试策略
     *
     * @param maxRetries   最大尝试次数（最小为1）
     * @param retryDelayMs 重试间隔（最小为1000ms）
     */
    public void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    /**
     * 设置显式的任务终结指令词
     */
    public void setFinishMarker(String finishMarker) {
        this.finishMarker = finishMarker;
    }

    /**
     * 设置输出结果在 Context 中的存储键
     */
    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    /**
     * 设置协作轮次上限，防止成本过载与逻辑死锁
     */
    public void setMaxTotalIterations(int maxTotalIterations) {
        this.maxTotalIterations = Math.max(1, maxTotalIterations);
    }

    /**
     * 注册团队拦截器（默认优先级）
     */
    public void addInterceptor(TeamInterceptor interceptor) {
        this.addInterceptor(interceptor, 0);
    }

    /**
     * 注册团队拦截器，并指定排序权重
     * * @param interceptor 拦截器实例
     *
     * @param index 排序权重（数值越小执行越靠前）
     */
    public void addInterceptor(TeamInterceptor interceptor, int index) {
        this.interceptorList.add(new RankEntity<>(interceptor, index));

        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }
    }

    /**
     * 设置团队指令模板提供者
     */
    public void setPromptProvider(TeamSystemPrompt promptProvider) {
        this.promptProvider = promptProvider;
    }

    /**
     * 配置主管推理时的 ChatOptions（如 Temperature, TopP 等参数）
     */
    public void setChatOptions(Consumer<ChatOptions> chatOptions) {
        this.chatOptions = chatOptions;
    }

    /**
     * 注入团队成员（Agent）
     * <p>每个成员必须拥有唯一的名称和明确的职责描述（Description），以便主管理解其用途。</p>
     *
     * @param agent 专家智能体或嵌套子团队
     */
    public void addAgent(Agent agent) {
        Objects.requireNonNull(agent.name(), "agent.name is required");
        Objects.requireNonNull(agent.description(), "agent.description is required for collaboration");

        agentMap.put(agent.name(), agent);
    }

    /**
     * 设置团队协作协议（即执行图的逻辑骨架）
     *
     * @param protocolFactory 协议工厂
     */
    public void setProtocol(TeamProtocolFactory protocolFactory) {
        Objects.requireNonNull(protocolFactory, "protocolFactory");
        this.protocol = protocolFactory.create(this);
    }

    // --- 属性 Getter ---

    public String getName() {
        return name;
    }

    public String getTraceKey() {
        if (traceKey == null) {
            traceKey = "__" + this.name;
        }

        return traceKey;
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
     * 获取任务终结标识
     * <p>逻辑：若未显式配置，则根据团队名称生成特征标记（例如：[TEAM_NAME_FINISH]）</p>
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

    public TeamSystemPrompt getPromptProvider() {
        return promptProvider;
    }

    /**
     * 获取当前配置采用的语言/地区环境
     */
    public Locale getLocale() {
        return promptProvider.getLocale();
    }
}