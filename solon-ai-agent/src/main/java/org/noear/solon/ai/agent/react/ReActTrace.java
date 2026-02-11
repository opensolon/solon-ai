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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.skill.SkillUtil;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowContextInternal;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 运行轨迹记录器 (状态机上下文)
 * <p>负责维护智能体推理过程中的短期记忆、执行路由、消息序列及上下文压缩。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActTrace implements AgentTrace {
    private static final Logger LOG = LoggerFactory.getLogger(ReActTrace.class);

    /**
     * 运行配置
     */
    private transient ReActAgentConfig config;
    /**
     * 运行选项
     */
    private transient ReActOptions options;
    /**
     * Agent 会话上下文
     */
    private transient AgentSession session;
    /**
     * 协作协议 (如 Team 模式)
     */
    private transient TeamProtocol protocol;
    /**
     * 协议注入的专用工具映射表
     */
    private transient final Map<String, FunctionTool> protocolToolMap = new LinkedHashMap<>();

    /**
     * 度量指标
     */
    private final Metrics metrics = new Metrics();

    /**
     * 任务提示词
     */
    private Prompt originalPrompt;
    /**
     * 工作记忆
     */
    private final Prompt workingMemory = new PromptImpl();

    /**
     * 迭代步数计数器
     */
    private AtomicInteger stepCounter = new AtomicInteger(0);
    /**
     * 工具调用计数器
     */
    private AtomicInteger toolCounter = new AtomicInteger(0);

    /**
     * 逻辑路由标识 (REASON, ACTION, END)
     */
    private volatile String route;
    /**
     * 最终回答内容 (Final Answer)
     */
    private volatile String finalAnswer;
    /**
     * 模型最近一次原始思考内容
     */
    private AssistantMessage lastReasonMessage;
    private String lastObservation;

    /**
     * 计划
     */
    private final List<String> plans = new CopyOnWriteArrayList<>();

    /**
     * 是否处于挂起状态（如：等待人工介入、异步回调或逻辑暂存）
     */
    private boolean pending;
    /**
     * 挂起原因（通常作为反馈给用户或审批者的提示信息）
     */
    private String pendingReason;

    private final Map<String, Object> extras = new ConcurrentHashMap<>();

    public Map<String, Object> getExtras() {
        return extras;
    }

    public Object getExtra(String key) {
        return extras.get(key);
    }

    public <T> T getExtraAs(String key) {
        return (T) extras.get(key);
    }

    public void setExtra(String key, Object val) {
        extras.put(key, val);
    }

    public ReActTrace() {
        this.route = ReActAgent.ID_REASON;
    }

    public ReActTrace(Prompt originalPrompt) {
        this();
        this.originalPrompt = originalPrompt;
    }

    public static ReActTrace getCurrent(FlowContext context) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_UNIT_TRACE_KEY);
        if (traceKey != null) {
            return context.getAs(traceKey);
        } else {
            return null;
        }
    }

    // --- 生命周期与状态管理 ---

    /**
     * 准备执行环境
     */
    protected void prepare(ReActAgentConfig config, ReActOptions options, AgentSession session, TeamProtocol protocol) {
        this.config = config;
        this.options = options;
        this.session = session;
        this.protocol = protocol;
        this.finalAnswer = null;

        //每次执行重置中断状态
        this.pending = false;
        this.pendingReason = null;
        ((FlowContextInternal)session.getSnapshot()).stopped(false);
    }

    protected void activeSkills() {
        //设置指令
        StringBuilder skillsInstruction = SkillUtil.activeSkills(options.getModelOptions(), originalPrompt, new StringBuilder());
        if (skillsInstruction.length() > 0) {
            options.setSkillInstruction(skillsInstruction.toString());
        }
    }

    protected void reset(Prompt originalPrompt) {
        // 1. 基础计数器重置
        stepCounter.set(0);
        toolCounter.set(0);

        // 2. 核心状态重置（非常重要，防止直接跳过推理进入上一次的 END 状态）
        this.route = ReActAgent.ID_REASON;
        this.finalAnswer = null;
        this.lastReasonMessage = null;
        this.lastObservation = null;

        // 3. 结构化数据重置
        plans.clear();
        workingMemory.clear();
        extras.clear();

        // 4. 指标重置（确保单次 Prompt 的 Token 消耗和时长统计准确）
        metrics.reset();

        // 5. 更新原始提示词
        setOriginalPrompt(originalPrompt);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Agent [{}] trace reset for a new task.", getAgentName());
        }
    }

    /**
     * 触发协作流挂起
     *
     * @param reason 挂起原因或需要人工确认的提示词
     */
    public void pending(String reason) {
        this.pending = true;
        this.pendingReason = reason;
        this.finalAnswer = reason;

        // 自动同步中断底层流程引擎
        if (session != null) {
            session.getSnapshot().stop();
        }
    }

    /**
     * 判定当前任务是否正在挂起等待
     */
    public boolean isPending() {
        return pending || (session != null && session.getSnapshot().isStopped());
    }

    /**
     * 获取挂起的原因或提示信息
     */
    public @Nullable String getPendingReason() {
        return pendingReason;
    }

    @Override
    public Metrics getMetrics() {
        return metrics;
    }

    public ReActAgentConfig getConfig() {
        return config;
    }

    public ReActOptions getOptions() {
        return options;
    }

    public AgentSession getSession() {
        return session;
    }

    /**
     * 获取流程快照快照
     */
    public FlowContext getContext() {
        if (session != null) {
            return session.getSnapshot();
        } else {
            return null;
        }
    }

    public TeamProtocol getProtocol() {
        return protocol;
    }

    /**
     * 注册协议内置工具
     */
    public void addProtocolTool(FunctionTool tool) {
        protocolToolMap.put(tool.name(), tool);
    }

    public FunctionTool getProtocolTool(String name) {
        return protocolToolMap.get(name);
    }

    public Collection<FunctionTool> getProtocolTools() {
        return protocolToolMap.values();
    }

    public String getAgentName() {
        return config.getName();
    }

    public Prompt getOriginalPrompt() {
        return originalPrompt;
    }

    protected void setOriginalPrompt(Prompt originalPrompt) {
        Objects.requireNonNull(originalPrompt, "OriginalPrompt cannot be null");

        this.originalPrompt = originalPrompt;
    }

    public Prompt getWorkingMemory() {
        return workingMemory;
    }

    public int getStepCount() {
        return stepCounter.get();
    }

    /**
     * 递增步数
     */
    public int nextStep() {
        int step = stepCounter.incrementAndGet();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Agent [{}] proceed to step: {}", getAgentName(), step);
        }
        return step;
    }

    public String getRoute() {
        return route;
    }

    /**
     * 更新路由状态
     */
    public void setRoute(String route) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Agent [{}] route changed: {} -> {}", getAgentName(), this.route, route);
        }
        this.route = route;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public AssistantMessage getLastReasonMessage() {
        return lastReasonMessage;
    }

    public void setLastReasonMessage(AssistantMessage lastReasonMessage) {
        this.lastReasonMessage = lastReasonMessage;
    }

    public String getLastObservation() {
        return lastObservation;
    }

    public void setLastObservation(String lastObservation) {
        this.lastObservation = lastObservation;
    }

    /**
     * 获取人性化历史记录格式
     */
    public String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : workingMemory.getMessages()) {
            if (msg instanceof UserMessage) {
                sb.append("[User] ").append(msg.getContent()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (Assert.isNotEmpty(am.getContent())) {
                    sb.append("[Assistant] ").append(am.getContent()).append("\n");
                }
                if (Assert.isNotEmpty(am.getToolCalls())) {
                    for (ToolCall call : am.getToolCalls()) {
                        sb.append("[Action] ").append(call.getName()).append(": ").append(call.getArguments()).append("\n");
                    }
                }
            } else if (msg instanceof ToolMessage) {
                sb.append("[Observation] ").append(msg.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 增加工具调用计数
     */
    public void incrementToolCallCount() {
        toolCounter.incrementAndGet();
    }

    /**
     * 获取已触发的工具调用总数
     */
    public int getToolCallCount() {
        return toolCounter.get();
    }

    //------------------

    /**
     * 获取当前执行计划
     */
    public List<String> getPlans() {
        return Collections.unmodifiableList(plans);
    }

    /**
     * 判断是否存在计划
     */
    public boolean hasPlans() {
        return !plans.isEmpty();
    }

    /**
     * 设置或重置计划
     *
     * @param newPlans 计划列表
     */
    public void setPlans(Collection<String> newPlans) {
        this.plans.clear();

        if (options.isPlanningMode()) {
            if (Assert.isNotEmpty(newPlans)) {
                // 过滤空行并修剪
                newPlans.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(this.plans::add);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Agent [{}] plans updated, total steps: {}", getAgentName(), plans.size());
            }
        }
    }

    /**
     * 添加单条计划步骤
     */
    public void addPlan(String step) {
        if (Assert.isNotEmpty(step)) {
            plans.add(step.trim());
        }
    }

    /**
     * 获取格式化的计划文本 (用于注入 System Prompt)
     */
    public String getFormattedPlans() {
        if (plans.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            sb.append(i + 1).append(". ").append(plans.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取当前的执行进度描述
     */
    public String getPlanProgress() {
        if (plans.isEmpty()) {
            return "";
        }
        // 基于当前已执行的步数（stepCount）推测进度（仅作为模型参考）
        return String.format("Total Steps: %d, Current Logic Step: %d", plans.size(), getStepCount());
    }
}