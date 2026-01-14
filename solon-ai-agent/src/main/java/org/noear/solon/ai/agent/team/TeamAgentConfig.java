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
import org.noear.solon.core.util.IgnoreCaseMap;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 团队协作配置（Team Configuration）
 * <p>核心职责：定义团队组织架构、决策大脑（Supervisor）、协作协议与运行治理策略。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamAgentConfig implements NonSerializable {
    private static final Logger LOG = LoggerFactory.getLogger(TeamAgentConfig.class);

    /** 团队唯一标识 */
    private String name;
    /** 状态跟踪键（用于隔离 Session 中的轨迹数据） */
    private volatile String traceKey;
    /** 显示标题 */
    private String title;
    /** 职能描述（供上层团队调度识别） */
    private String description;
    /** 档案信息（能力与边界定义） */
    private AgentProfile profile;

    /** 调度中心模型（Supervisor），负责分发任务、协调专家与审核结果 */
    private final ChatModel chatModel;
    /** 调度中心推理参数（控制采样随机性、Token 等） */
    private Consumer<ChatOptions> chatOptions;

    /** 成员名录（有序不计大小写存储专家 Agent） */
    private final Map<String, Agent> agentMap = new IgnoreCaseMap<>();
    /** 协作协议（定义任务流转的逻辑骨架，默认层级式） */
    private volatile TeamProtocol protocol = TeamProtocols.HIERARCHICAL.create(this);
    /** 执行图微调钩子（支持在协议骨架上增加业务节点或连线） */
    private Consumer<GraphSpec> graphAdjuster;

    /** 任务终结符（Supervisor 输出此词时视为协作结束） */
    private String finishMarker;
    /** 结果回填 Key（将 Final Answer 自动存入 FlowContext） */
    private String outputKey;

    /** 系统提示词（System Prompt）模板提供者 */
    private TeamSystemPrompt systemPrompt = TeamSystemPromptCn.getDefault();
    /** 运行时全局配置选项 */
    private final TeamOptions defaultOptions = new TeamOptions();

    public TeamAgentConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // --- 配置注入 (Protected) ---

    protected void setName(String name) { this.name = name; }
    protected void setTitle(String title) { this.title = title; }
    protected void setDescription(String description) { this.description = description; }
    protected void setProfile(AgentProfile profile) { this.profile = profile; }
    protected void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) { this.graphAdjuster = graphAdjuster; }
    protected void setFinishMarker(String finishMarker) { this.finishMarker = finishMarker; }
    protected void setOutputKey(String outputKey) { this.outputKey = outputKey; }
    protected void setTeamSystem(TeamSystemPrompt promptProvider) { this.systemPrompt = promptProvider; }
    protected void setChatOptions(Consumer<ChatOptions> chatOptions) { this.chatOptions = chatOptions; }

    /**
     * 注册团队成员（专家）
     * @param agent 具备明确职责描述的智能体实例
     */
    protected void addAgent(Agent agent) {
        Objects.requireNonNull(agent.name(), "agent.name is required");
        Objects.requireNonNull(agent.description(), "agent.description is required");

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgentConfig [{}] register agent: {}", name, agent.name());
        }
        agentMap.put(agent.name(), agent);
    }

    /**
     * 设置协作协议（生成执行图逻辑骨架）
     */
    protected void setProtocol(TeamProtocolFactory protocolFactory) {
        Objects.requireNonNull(protocolFactory, "protocolFactory is null");
        this.protocol = protocolFactory.create(this);

        if (LOG.isInfoEnabled()) {
            LOG.info("TeamAgentConfig [{}] switched protocol to: {}", name, protocol.getClass().getSimpleName());
        }
    }

    // --- 属性获取 (Public) ---

    public TeamOptions getDefaultOptions() { return defaultOptions; }
    public String getName() { return name; }

    /**
     * 获取 Trace 存储键（默认使用双下划线前缀隔离）
     */
    public String getTraceKey() {
        if (traceKey == null) {
            traceKey = "__" + this.name;
        }
        return traceKey;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }

    public AgentProfile getProfile() {
        if (profile == null) profile = new AgentProfile();
        return profile;
    }

    public ChatModel getChatModel() { return chatModel; }
    public Consumer<ChatOptions> getChatOptions() { return chatOptions; }
    public Map<String, Agent> getAgentMap() { return agentMap; }
    public TeamProtocol getProtocol() { return protocol; }
    public Consumer<GraphSpec> getGraphAdjuster() { return graphAdjuster; }

    /**
     * 获取终结标识符（兜底生成模式）
     */
    public String getFinishMarker() {
        if (finishMarker == null) {
            finishMarker = "[" + (name != null ? name.toUpperCase() : "TEAM") + "_FINISH]";
        }
        return finishMarker;
    }

    public String getOutputKey() { return outputKey; }

    public String getTeamSystem(TeamTrace trace, FlowContext context) {
        return systemPrompt.getSystemPromptFor(trace, context);
    }

    public Locale getLocale() { return systemPrompt.getLocale(); }
}