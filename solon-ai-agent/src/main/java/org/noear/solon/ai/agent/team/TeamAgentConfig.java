/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.NonSerializable;
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
@Preview("3.8.1")
public class TeamAgentConfig implements NonSerializable {
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

    private AgentProfile profile;

    /**
     * 调度中心模型（Supervisor Model），负责解析任务、选择专家及审核产出
     */
    private final ChatModel chatModel;

    /**
     * 调度中心推理配置（用于精细化控制 Supervisor 的采样随机性、Token 限制等）
     */
    private Consumer<ChatOptions> chatOptions;

    /**
     * 团队成员名录，存储所有参与协作的专家智能体（Agent）实例，保留添加顺序
     */
    private final LinkedHashMap<String, Agent> agentMap = new LinkedHashMap<>();

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
     * 系统提示词（System Prompt）模板提供者，支持多语言动态适配
     */
    private TeamSystemPrompt systemPrompt = TeamSystemPromptCn.getDefault();

    private final TeamOptions defaultOptions = new TeamOptions();

    /**
     * 基于指定的推理模型初始化团队配置
     *
     * @param chatModel 担任“主管”角色的 ChatModel
     */
    public TeamAgentConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // --- 配置注入 (Protected) ---

    /**
     * 设置团队唯一标识名称
     */
    protected void setName(String name) {
        this.name = name;
    }

    /**
     * 设置团队可视化标题
     */
    protected void setTitle(String title) {
        this.title = title;
    }

    /**
     * 设置团队职能描述
     */
    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setProfile(AgentProfile profile) {
        this.profile = profile;
    }

    /**
     * 注入自定义的流程图调整逻辑
     */
    protected void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) {
        this.graphAdjuster = graphAdjuster;
    }

    /**
     * 设置显式的任务终结指令词
     */
    protected void setFinishMarker(String finishMarker) {
        this.finishMarker = finishMarker;
    }

    /**
     * 设置输出结果在 Context 中的存储键
     */
    protected void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    /**
     * 设置团队指令模板提供者
     */
    protected void setTeamSystem(TeamSystemPrompt promptProvider) {
        this.systemPrompt = promptProvider;
    }

    /**
     * 配置主管推理时的 ChatOptions（如 Temperature, TopP 等参数）
     */
    protected void setChatOptions(Consumer<ChatOptions> chatOptions) {
        this.chatOptions = chatOptions;
    }

    /**
     * 注入团队成员（Agent）
     * <p>每个成员必须拥有唯一的名称和明确的职责描述（Description），以便主管理解其用途。</p>
     *
     * @param agent 专家智能体或嵌套子团队
     */
    protected void addAgent(Agent agent) {
        Objects.requireNonNull(agent.name(), "agent.name is required");
        Objects.requireNonNull(agent.description(), "agent.description is required for collaboration");

        agentMap.put(agent.name(), agent);
    }

    /**
     * 设置团队协作协议（即执行图的逻辑骨架）
     *
     * @param protocolFactory 协议工厂
     */
    protected void setProtocol(TeamProtocolFactory protocolFactory) {
        Objects.requireNonNull(protocolFactory, "protocolFactory");
        this.protocol = protocolFactory.create(this);
    }

    // --- 属性获取 (Public) ---


    public TeamOptions getDefaultOptions() {
        return defaultOptions;
    }

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

    public AgentProfile getProfile() {
        if (profile == null) {
            profile = new AgentProfile();
        }
        return profile;
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

    public String getTeamSystem(TeamTrace trace, FlowContext context) {
        return systemPrompt.getSystemPromptFor(trace, context);
    }

    /**
     * 获取当前配置采用的语言/地区环境
     */
    public Locale getLocale() {
        return systemPrompt.getLocale();
    }
}