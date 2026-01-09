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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 团队协作轨迹（记录团队内部各智能体的协作流转状态与历史）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamTrace {
    /**
     * 团队配置实例（不参与序列化）
     */
    private transient TeamConfig config;

    /**
     * 当前 Agent 会话实例（不参与序列化）
     */
    private transient AgentSession session;

    /**
     * 当前正在执行任务的智能体名称
     */
    private String agentName;

    /**
     * 团队收到的原始提示词或当前阶段的提示词
     */
    private Prompt prompt;

    /**
     * 协作步骤详情列表（有序记录每一步的执行者、内容及耗时）
     */
    private final List<TeamStep> steps = new ArrayList<>();

    /**
     * 当前路由目标（决定下一个执行的 Agent ID 或 结束标识）
     */
    private volatile String route;

    /**
     * 调度器（Supervisor）的最后一次原始决策结论文本
     */
    private volatile String lastDecision;

    /**
     * 当前团队协作的迭代次数计数器（用于防止失控或达到最大深度限制）
     */
    private final AtomicInteger iterations;

    /**
     * 协议相关上下文（供不同协作协议存储自定义的运行时数据）
     */
    private final Map<String, Object> protocolContext = new ConcurrentHashMap<>();

    /**
     * 团队输出的最终答案
     */
    private String finalAnswer;

    /**
     * 缓存的格式化历史文本（避免重复流式计算）
     */
    private String cachedFormattedHistory;

    /**
     * 历史记录是否需要更新缓存的标识
     */
    private boolean isUpdateHistoryCache = true;

    /**
     * 默认构造函数（主要用于反序列化）
     */
    public TeamTrace() {
        this.iterations = new AtomicInteger(0);
    }

    /**
     * 根据初始提示词创建轨迹
     *
     * @param prompt 初始提示词
     */
    public TeamTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }

    /**
     * 准备运行时环境
     *
     * @param config    团队配置
     * @param session   当前会话
     * @param agentName 当前 Agent 名称
     */
    protected void prepare(TeamConfig config, AgentSession session, String agentName) {
        this.config = config;
        this.session = session;
        this.agentName = agentName;
    }

    // ... Getter & Setter 保持不变 ...

    /**
     * 获取当前处理的智能体名称
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 获取团队配置
     */
    public TeamConfig getConfig() {
        return config;
    }

    /**
     * 获取 Agent 会话
     */
    public AgentSession getSession() {
        return session;
    }

    /**
     * 获取当前使用的协议
     */
    public TeamProtocol getProtocol() {
        return config.getProtocol();
    }

    /**
     * 获取当前提示词
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 设置当前提示词
     */
    protected void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取当前路由目标
     */
    public String getRoute() {
        return route;
    }

    /**
     * 设置路由目标
     */
    public void setRoute(String route) {
        this.route = route;
    }

    /**
     * 获取调度器决策文本
     */
    public String getLastDecision() {
        return lastDecision;
    }

    /**
     * 设置调度器决策文本
     */
    public void setLastDecision(String decision) {
        this.lastDecision = decision;
    }

    /**
     * 获取当前迭代次数
     */
    public int getIterationsCount() {
        return iterations.get();
    }

    /**
     * 重置迭代次数
     */
    public void resetIterationsCount() {
        iterations.set(0);
    }

    /**
     * 增加并获取下一次迭代计数
     */
    public int nextIterations() {
        return iterations.incrementAndGet();
    }

    /**
     * 获取协议上下文 Map
     */
    public Map<String, Object> getProtocolContext() {
        return protocolContext;
    }

    /**
     * 获取步骤总数
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * 添加一个协作步骤
     *
     * @param agentName 执行者名称
     * @param content   执行产出的内容
     * @param duration  执行耗时（毫秒）
     */
    public void addStep(String agentName, String content, long duration) {
        steps.add(new TeamStep(agentName, content, duration));
        isUpdateHistoryCache = true;
    }

    /**
     * 获取格式化的协作历史文本
     * <p>常用于提供给 Supervisor 作为决策依据，格式为 "[Agent]: Content"</p>
     */
    public String getFormattedHistory() {
        if (steps.isEmpty()) {
            return "No progress yet.";
        }

        if (isUpdateHistoryCache) {
            cachedFormattedHistory = steps.stream()
                    .map(step -> String.format("[%s]: %s", step.getAgentName(), step.getContent()))
                    .collect(Collectors.joining("\n"));
            isUpdateHistoryCache = false;
        }

        return cachedFormattedHistory;
    }

    /**
     * 循环检测逻辑
     * <p>检查是否陷入了简单的双人重复循环（A-B-A-B）或同一个 Agent 重复输出相同内容</p>
     *
     * @return 是否存在死循环风险
     */
    public boolean isLooping() {
        int n = steps.size();
        if (n < 4) return false;

        String lastAgent = steps.get(n - 1).getAgentName();
        String lastContent = steps.get(n - 1).getContent();

        // 1. 检查单一 Agent 复读：同一个 Agent 输出了跟之前完全一致的内容
        for (int i = 0; i < n - 1; i++) {
            TeamStep prev = steps.get(i);
            if (prev.getAgentName().equals(lastAgent) && prev.getContent().equals(lastContent)) {
                return true;
            }
        }

        // 2. 检查 A-B-A-B 模式检测：需要至少 8 步来判定长效循环
        if (n >= 8) {
            return steps.get(n - 1).getAgentName().equals(steps.get(n - 3).getAgentName()) &&
                    steps.get(n - 2).getAgentName().equals(steps.get(n - 4).getAgentName());
        }

        return false;
    }

    /**
     * 获取所有步骤列表
     */
    public List<TeamStep> getSteps() {
        return steps;
    }

    /**
     * 获取最终答案
     */
    public String getFinalAnswer() {
        return finalAnswer;
    }

    /**
     * 设置最终答案
     */
    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    /**
     * 单个协作步骤实体（不可变对象）
     */
    public static class TeamStep {
        /**
         * 该步骤的执行智能体名称
         */
        private final String agentName;

        /**
         * 该步骤产出的具体文本内容
         */
        private final String content;

        /**
         * 运行该步骤所消耗的时间（毫秒）
         */
        private final long duration;

        public TeamStep(String agentName, String content, long duration) {
            this.agentName = agentName;
            this.content = content;
            this.duration = duration;
        }

        public String getAgentName() {
            return agentName;
        }

        public String getContent() {
            return content;
        }

        public long getDuration() {
            return duration;
        }
    }
}