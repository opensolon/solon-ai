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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 团队协作轨迹（记录团队内部各智能体的协作流转状态与历史）
 *
 * <p>核心职责：持久化任务上下文、追踪路由决策、维护协议私有状态以及提供格式化的对话历史。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamTrace implements AgentTrace {
    /** 团队配置（不参与序列化） */
    private transient TeamConfig config;
    /** Agent 会话上下文（不参与序列化） */
    private transient AgentSession session;

    /** 当前正在执行任务的智能体名称 */
    private String agentName;
    /** 当前阶段的提示词（可能是原始提示词，也可能是协议裁剪后的提示词） */
    private Prompt prompt;
    /** 协作步骤列表（按时间顺序记录执行者及其产出） */
    private final List<TeamStep> steps = new ArrayList<>();

    /** 当前路由指向的目标节点（Agent 名称或 ID_END） */
    private volatile String route;
    /** 调度器（Supervisor）最后一次生成的决策原文 */
    private volatile String lastDecision;
    /** 迭代次数计数器（用于防止协作陷入死循环或达到深度上限） */
    private final AtomicInteger iterationCounter;

    /** 协议私有上下文（供 TeamProtocol 存储特定的运行时数据，如 A2A 的 Handoff 标记） */
    private final Map<String, Object> protocolContext = new ConcurrentHashMap<>();

    /** 团队输出的最终答案 */
    private String finalAnswer;
    /** 格式化历史的缓存（避免大上下文下的高频字符串拼接） */
    private String cachedFormattedHistory;
    /** 标记缓存是否失效 */
    private boolean isUpdateHistoryCache = true;

    public TeamTrace() {
        this.iterationCounter = new AtomicInteger(0);
    }

    public TeamTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }

    /**
     * 判断当前是否处于初始阶段（即尚未有任何 Agent 执行产出）
     */
    public boolean isInitial() {
        return steps.isEmpty();
    }

    /**
     * 获取最近一个步骤的执行产出内容
     */
    public String getLastStepContent() {
        if (steps.isEmpty()) return "";
        return steps.get(steps.size() - 1).getContent();
    }

    /**
     * 获取最近一次由“非主管（Non-Supervisor）”智能体产出的内容
     * <p>常用于协议在路由前提取专家的最终结论，过滤掉调度器的决策指令。</p>
     */
    public String getLastAgentContent() {
        for (int i = steps.size() - 1; i >= 0; i--) {
            TeamStep step = steps.get(i);
            if (!Agent.ID_SUPERVISOR.equals(step.getAgentName())) {
                return step.getContent();
            }
        }
        return "";
    }

    /**
     * 准备运行时环境（由框架内部调用）
     */
    protected void prepare(TeamConfig config, AgentSession session, String agentName) {
        this.config = config;
        this.session = session;
        this.agentName = agentName;
    }

    // --- 属性访问器 ---

    public String getAgentName() { return agentName; }
    public TeamConfig getConfig() { return config; }
    public AgentSession getSession() { return session; }
    public TeamProtocol getProtocol() { return config.getProtocol(); }
    public Prompt getPrompt() { return prompt; }
    protected void setPrompt(Prompt prompt) { this.prompt = prompt; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getLastDecision() { return lastDecision; }
    public void setLastDecision(String decision) { this.lastDecision = decision; }
    public int getIterationsCount() { return iterationCounter.get(); }
    public void resetIterationsCount() { iterationCounter.set(0); }
    public int nextIterations() { return iterationCounter.incrementAndGet(); }

    /**
     * 获取协议相关的运行时上下文 Map
     */
    public Map<String, Object> getProtocolContext() {
        return protocolContext;
    }

    /**
     * 获取当前协作的总步数
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * 添加协作步骤并标记历史缓存失效
     */
    public void addStep(String agentName, String content, long duration) {
        steps.add(new TeamStep(agentName, content, duration));
        isUpdateHistoryCache = true;
    }

    /**
     * 获取格式化的协作历史文本
     * <p>采用 Markdown 块格式（### Agent: \n Content），增强 LLM 对对话边界的识别能力。</p>
     */
    public String getFormattedHistory() {
        if (steps.isEmpty()) {
            return "No progress yet.";
        }

        if (isUpdateHistoryCache) {
            cachedFormattedHistory = steps.stream()
                    .map(step -> String.format("### %s:\n%s", step.getAgentName(), step.getContent()))
                    .collect(Collectors.joining("\n\n"));
            isUpdateHistoryCache = false;
        }

        return cachedFormattedHistory;
    }

    /**
     * 协作流异常循环检测
     * <p>检测包含：1.单人复读模式；2.双人 A-B-A-B 镜像模式。</p>
     *
     * @return 若存在死循环风险返回 true
     */
    public boolean isLooping() {
        int n = steps.size();
        if (n < 4) return false;

        TeamStep lastStep = steps.get(n - 1);
        String lastAgent = lastStep.getAgentName();
        String lastContent = lastStep.getContent();

        // 过滤空内容，防止初始化异常导致的误判
        if (lastContent == null || lastContent.trim().isEmpty()) return false;

        // 1. 检查单一 Agent 复读检测
        for (int i = 0; i < n - 1; i++) {
            TeamStep prev = steps.get(i);
            if (prev.getAgentName().equals(lastAgent) && Objects.equals(prev.getContent(), lastContent)) {
                return true;
            }
        }

        // 2. 检查多 Agent 镜像循环（A-B-A-B）
        if (n >= 8) {
            return steps.get(n - 1).getAgentName().equals(steps.get(n - 3).getAgentName()) &&
                    steps.get(n - 2).getAgentName().equals(steps.get(n - 4).getAgentName());
        }

        return false;
    }

    /**
     * 获取所有协作步骤（返回只读视图以保证协议安全性）
     */
    public List<TeamStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    /**
     * 协作步骤详情实体（Immutable）
     */
    public static class TeamStep {
        private final String agentName;
        private final String content;
        private final long duration;

        public TeamStep(String agentName, String content, long duration) {
            this.agentName = agentName;
            this.content = content;
            this.duration = duration;
        }

        public String getAgentName() { return agentName; }
        public String getContent() { return content; }
        public long getDuration() { return duration; }
    }
}