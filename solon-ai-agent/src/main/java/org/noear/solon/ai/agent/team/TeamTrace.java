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
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 团队协作轨迹（核心治理对象）
 *
 * <p>该类是多智能体协作环境中的“黑匣子”与“状态总线”，负责记录任务在智能体团队内部流转的全生命周期状态。</p>
 * <p>其核心能力包括：</p>
 * <ul>
 * <li><b>上下文持久化</b>：追踪并存储任务指令、协作步骤及各专家的阶段性产出。</li>
 * <li><b>协议状态隔离</b>：提供专属空间供 {@link TeamProtocol} 存储运行时私有数据（如 A2A 协议的 Handoff 标记）。</li>
 * <li><b>路由决策回溯</b>：记录调度器（Supervisor）的决策链路，辅助解决任务分发死循环。</li>
 * <li><b>性能与开销监控</b>：统计各节点的执行耗时，提供协作链路的量化数据。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamTrace implements AgentTrace {
    /** 关联的团队配置（生命周期内稳定，不参与持久化序列化） */
    private transient TeamConfig config;
    private transient TeamOptions options;
    /** 当前活跃的会话上下文（持有底层 LLM 记忆，不参与持久化序列化） */
    private transient AgentSession session;

    /** 历史记录的格式化快照（Markdown 优化版） */
    private transient String cachedFormattedHistory;
    /** 脏位标记：用于实现格式化历史的延迟加载与缓存失效逻辑 */
    private transient boolean isUpdateHistoryCache = true;

    /** 当前正在处理任务的 Agent 标识 */
    private String agentName;
    /** 任务的活跃提示词（随协作阶段可能动态裁剪或重组） */
    private Prompt prompt;
    /** 协作流水账：按时间轴线性记录的执行步骤详情 */
    private final List<TeamStep> steps = new CopyOnWriteArrayList<>();

    /** 路由决策结果：指向下一个待执行的 Agent 名称或系统终止符 (ID_END) */
    private volatile String route;
    /** 记录调度器（Supervisor）输出的原始推理文本，用于异常复盘与自省 */
    private volatile String lastDecision;
    /** 记录最后运行的智能体名字 */
    private volatile String lastAgentName;
    /** 迭代安全计数器：限制协作的最大深度，防止 LLM 幻觉导致的无限递归 */
    private final AtomicInteger iterationCounter;

    /** 协议私有存储域：允许不同协作模式存储非标准数据（如竞标书、移交说明等） */
    private final Map<String, Object> protocolContext = new ConcurrentHashMap<>();

    /** 最终对外交付的结构化答案 */
    private String finalAnswer;

    public TeamTrace() {
        this.iterationCounter = new AtomicInteger(0);
    }

    public TeamTrace(Prompt prompt) {
        this();
        this.prompt = prompt;
    }

    /**
     * 是否为初始状态（尚未产生实质性协作产出）
     */
    public boolean isInitial() {
        return steps.isEmpty();
    }

    /**
     * 提取最近一轮的产出内容
     */
    public String getLastStepContent() {
        if (steps.isEmpty()) return "";
        return steps.get(steps.size() - 1).getContent();
    }

    /**
     * 提取最近一位专家（非 Supervisor）的结论
     * <p>在层次化架构中，此方法用于过滤掉调度器的决策性文本，仅获取具体的业务处理结果。</p>
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
     * 运行时环境初始化
     */
    protected void prepare(TeamConfig config, TeamOptions options, AgentSession session, String agentName) {
        this.config = config;
        this.options = options;
        this.session = session;
        this.agentName = agentName;
    }

    // --- 核心元数据访问 ---

    public String getAgentName() { return agentName; }
    public TeamConfig getConfig() { return config; }

    public TeamOptions getOptions() {
        return options;
    }

    public AgentSession getSession() { return session; }
    /**
     * 获取流程上下文
     */
    public FlowContext getContext(){
        if(session != null){
            return session.getSnapshot();
        } else {
            return null;
        }
    }
    public TeamProtocol getProtocol() { return config.getProtocol(); }
    public Prompt getPrompt() { return prompt; }
    protected void setPrompt(Prompt prompt) { this.prompt = prompt; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getLastDecision() { return lastDecision; }
    public void setLastDecision(String decision) { this.lastDecision = decision; }
    public String getLastAgentName() { return lastAgentName; }
    public void setLastAgentName(String agentName) { this.lastAgentName = agentName; }
    public int getIterationsCount() { return iterationCounter.get(); }
    public void resetIterationsCount() { iterationCounter.set(0); }
    public int nextIterations() { return iterationCounter.incrementAndGet(); }

    /**
     * 获取协议共享上下文（用于 A2A 移交、市场竞标等复杂逻辑）
     */
    public Map<String, Object> getProtocolContext() {
        return protocolContext;
    }

    /**
     * 获取当前总迭代步数（包含调度与执行）
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * 压入新的执行足迹并使缓存失效
     */
    public void addStep(String agentName, String content, long duration) {
        steps.add(new TeamStep(agentName, content, duration));
        isUpdateHistoryCache = true;
    }

    /**
     * 获取标准化的协作历史报告
     * <p>使用 Markdown 语义增强角色边界，帮助 LLM 更好地区分不同 Agent 的角色与贡献。</p>
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
     * 获取不可变的步骤列表视图
     */
    public List<TeamStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    /**
     * 协作足迹详情（单次执行的审计快照）
     */
    public static class TeamStep {
        /** 执行此步骤的智能体 */
        private final String agentName;
        /** 该步骤产出的内容快照 */
        private final String content;
        /** 本轮推理消耗的物理耗时（毫秒） */
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