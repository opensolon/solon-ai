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

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.SkillUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 团队协作轨迹（Team Trace）
 *
 * <p>核心职责：记录任务在 Agent 团队内部流转的全生命周期状态，充当协作“黑匣子”与“状态总线”。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamTrace implements AgentTrace {
    private static final Logger LOG = LoggerFactory.getLogger(TeamTrace.class);

    /** 关联团队配置 */
    private transient TeamAgentConfig config;
    /** 运行时选项 */
    private transient TeamOptions options;
    /** 当前活跃会话（持有 LLM 上下文记忆） */
    private transient AgentSession session;

    /** 当前 Agent 标识 */
    private String agentName;
    /** 当前任务提示词（随协作阶段动态变化） */
    private Prompt prompt;
    /** 协作流水账：按时间轴记录执行详情 */
    private final List<TeamRecord> records = new CopyOnWriteArrayList<>();

    /** 路由决策：指向下一个 Agent 或 ID_END */
    private volatile String route;
    /** 调度器原始决策文本（用于异常复盘） */
    private volatile String lastDecision;
    /** 最后运行的专家 Agent 名字 */
    private volatile String lastAgentName;
    /** 迭代安全计数器（防止无限循环） */
    private final AtomicInteger turnCounter;
    /** 度量指标 */
    private final Metrics metrics = new Metrics();

    /** 协议私有存储空间（供 TeamProtocol 存储私有状态） */
    private final Map<String, Object> protocolContext = new ConcurrentHashMap<>();

    /** 最终交付答案 */
    private String finalAnswer;

    public TeamTrace() {
        this.turnCounter = new AtomicInteger(0);
    }

    public TeamTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }

    protected void activeSkills() {
        //设置指令
        StringBuilder combinedInstruction = SkillUtil.activeSkills(options.getModelOptions(), prompt);
        if (combinedInstruction.length() > 0) {
            options.setSkillInstruction(combinedInstruction.toString());
        }
    }

    /** 是否为初始状态 */
    public boolean isInitial() {
        return records.isEmpty();
    }

    /** 提取最近一位专家 Agent（非 Supervisor）的内容 */
    public String getLastAgentContent() {
        for (int i = records.size() - 1; i >= 0; i--) {
            TeamRecord record = records.get(i);
            if (record.isAgent()) return record.getContent();
        }
        return "";
    }

    public long getLastAgentDuration() {
        for (int i = records.size() - 1; i >= 0; i--) {
            TeamRecord record = records.get(i);
            if (record.isAgent()) return record.getDuration();
        }
        return 0L;
    }

    /** 运行时环境准备 */
    protected void prepare(TeamAgentConfig config, TeamOptions options, AgentSession session, String agentName) {
        this.config = config;
        this.options = options;
        this.session = session;
        this.agentName = agentName;
    }

    // --- 属性访问 ---

    @Override
    public Metrics getMetrics() {
        return metrics;
    }

    public String getAgentName() { return agentName; }
    public TeamAgentConfig getConfig() { return config; }
    public TeamOptions getOptions() { return options; }
    public AgentSession getSession() { return session; }

    public FlowContext getContext() {
        return (session != null) ? session.getSnapshot() : null;
    }

    public TeamProtocol getProtocol() { return config.getProtocol(); }
    public Prompt getPrompt() { return prompt; }
    public void setPrompt(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt cannot be null");
        this.prompt = prompt;
    }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getLastDecision() { return lastDecision; }
    public void setLastDecision(String decision) { this.lastDecision = decision; }
    public String getLastAgentName() { return lastAgentName; }
    public void setLastAgentName(String agentName) { this.lastAgentName = agentName; }
    public int getTurnCount() { return turnCounter.get(); }
    public void resetTurnCount() { turnCounter.set(0); }
    public int nextTurn() { return turnCounter.incrementAndGet(); }

    /** 获取协议私有上下文 */
    public Map<String, Object> getProtocolContext() { return protocolContext; }
    public void resetProtocolContext(){
        protocolContext.clear();
    }

    /** 获取协议状态快照（JSON 格式，供 Agent 感知全局进度） */
    public String getProtocolDashboardSnapshot() {
        return ONode.serialize(protocolContext);
    }

    public <T> T getProtocolContextAs(String key) {
        return (T) protocolContext.get(key);
    }

    public int getRecordCount() { return records.size(); }

    /** 记录执行足迹 */
    public void addRecord(ChatRole role, String source, String content, long duration) {
        records.add(new TeamRecord(role, source, content, duration));

        if (LOG.isDebugEnabled() && config != null) {
            LOG.debug("TeamTrace [{}] record added: role={}, source={}, duration={}ms",
                    config.getName(), role, source, duration);
        }
    }

    /** 获取全量格式化历史 */
    public String getFormattedHistory() { return getFormattedHistory(0, true); }

    public String getFormattedHistory(int windowSize) { return getFormattedHistory(windowSize, true); }

    /**
     * 渲染协作历史（Markdown 格式）
     * @param windowSize 限制返回的步数（0 为不限）
     * @param includeSystem 是否包含调度器决策逻辑
     */
    public String getFormattedHistory(int windowSize, boolean includeSystem) {
        if (records.isEmpty()) return "No progress yet.";

        List<TeamRecord> recordList = records;
        if (!includeSystem) {
            recordList = records.stream().filter(TeamRecord::isAgent).collect(Collectors.toList());
        }

        if (windowSize > 0 && recordList.size() > windowSize) {
            recordList = recordList.subList(recordList.size() - windowSize, recordList.size());
        }

        return recordList.stream()
                .map(record -> {
                    String title = record.isAgent() ? "Expert Output" : "System Instruction";
                    return String.format("### %s from [%s]:\n%s", title, record.getSource(), record.getContent());
                })
                .collect(Collectors.joining("\n\n"));
    }

    public List<TeamRecord> getRecords() { return Collections.unmodifiableList(records); }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    /** 协作足迹详情 */
    public static class TeamRecord {
        private final ChatRole role;
        private final String source;
        private final String content;
        private final long duration;

        public TeamRecord(ChatRole role, String source, String content, long duration) {
            this.role = role;
            this.source = source;
            this.content = content;
            this.duration = duration;
        }

        public ChatRole getRole() { return role; }
        public boolean isAgent() { return ChatRole.ASSISTANT == role; }
        public String getSource() { return source; }
        public String getContent() { return content; }
        public long getDuration() { return duration; }
    }
}