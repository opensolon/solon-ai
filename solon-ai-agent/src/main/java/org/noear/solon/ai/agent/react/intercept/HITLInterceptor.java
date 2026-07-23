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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 人工介入拦截器 (Human-in-the-Loop Interceptor)
 *
 * <p>该拦截器通过 ReAct 协议的生命周期钩子实现流程管控：</p>
 *
 * <p>流式输出时会推送 HITL 审查块，便于前端按类型渲染：</p>
 * <ul>
 * <li>{@link HITLPendingChunk} —— 首次拦截挂起，携带 {@link HITLTask}，用于展示审批卡片</li>
 * <li>{@link HITLDecidedChunk} —— 决策生效，携带 {@link HITLDecision}，用于关闭/更新审批卡片</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITLInterceptor extends AbsReActInterceptor {
    private final Map<String, HITLStrategy> strategyMap = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Map<String, Object>> approvedCallback;

    /**
     * 注册工具介入策略
     */
    public HITLInterceptor onTool(String toolName, HITLStrategy strategy) {
        strategyMap.put(toolName, strategy);
        return this;
    }

    /**
     * 快速注册敏感工具（使用默认敏感策略）
     */
    public HITLInterceptor onSensitiveTool(String... toolNames) {
        HITLSensitiveStrategy toolDesc = new HITLSensitiveStrategy();
        for (String name : toolNames) onTool(name, toolDesc);
        return this;
    }

    /**
     * 恢复执行时：若批挂起任务均已有决策，且仍保留 lastReason，则强制回到 ACTION，
     * 避免再次 Reason 导致 tool call uuid 漂移、决策无法命中、PENDING_TASKS 无法清理。
     */
    @Override
    public void onAgentStart(ReActTrace trace) {
        if (trace.getSession() == null || trace.getLastReasonMessage() == null) {
            return;
        }

        List<HITLTask> pending = HITL.getPendingTasks(trace.getSession());
        if (pending.isEmpty()) {
            return;
        }

        for (HITLTask t : pending) {
            if (HITL.getDecision(trace.getSession(), t) == null) {
                return; // 尚有未决策项，保持默认 Reason 路由
            }
        }
        // 全部已决策：复用挂起前的 tool_calls 进入 Action 应用决策
        trace.setRoute(ReActAgent.ID_ACTION);
    }
    
    /**
     * 批级预检：扫全量 tool call。
     * <ul>
     * <li>存在未决策的敏感 call → 整批挂起（零执行）</li>
     * <li>全部已有决策 → 应用改参 / skip / reject</li>
     * </ul>
     * <p>ActionTask 在此之后若 session.pending 则不进入任何 doAction。</p>
     */
    @Override
    public void onActionStart(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty() || strategyMap.isEmpty()) {
            return;
        }

        Map<String, Long> nameCount = new HashMap<>();
        for (ToolExchanger ex : toolCalls) {
            if (ex.getToolName() != null) {
                nameCount.merge(ex.getToolName(), 1L, Long::sum);
            }
        }

        // Phase-1: 评估，区分 pending / ready
        List<HITLTask> pending = new ArrayList<>();
        List<Object[]> ready = new ArrayList<>(); // [ex, decision]
        int hitlTargetCount = 0;

        for (ToolExchanger ex : toolCalls) {
            HITLStrategy strategy = strategyMap.get(ex.getToolName());
            if (strategy == null) {
                continue;
            }

            String comment = strategy.evaluate(trace, ex.getArgs());
            if (comment == null) {
                continue;
            }

            hitlTargetCount++;
            HITLDecision decision = resolveDecision(trace, ex.getCallId(), ex.getToolName(), nameCount);
            if (decision == null) {
                pending.add(new HITLTask(ex.getCallId(), ex.getToolName(), ex.getArgs(), comment));
            } else {
                ready.add(new Object[]{ex, decision});
            }
        }

        // Phase-2a: 有未决 → 整批挂起，不 apply（避免半批副作用）
        if (!pending.isEmpty()) {
            suspendBatch(trace, pending);
            return;
        }

        // Phase-2b: 全部有决策 → 清挂起列表后应用
        // （上一轮 suspend 写入的 PENDING_TASKS 必须在此清掉，否则 getPendingTask 仍非空）
        if (!ready.isEmpty()) {
            trace.getContext().remove(HITL.PENDING_TASKS);
            trace.getContext().remove(HITL.LAST_INTERVENED);
            for (Object[] pair : ready) {
                applyDecision(trace, (ToolExchanger) pair[0], (HITLDecision) pair[1], hitlTargetCount);
            }
        }
    }

    /**
     * 单工具执行前：批逻辑已在 onActionStart。
     * 保留薄 fallback，兼容未走批 Start 的路径。
     */
    @Override
    public void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
        HITLStrategy strategy = strategyMap.get(toolExchanger.getToolName());
        if (strategy == null) {
            return;
        }

        // 若批挂起列表已存在，说明本轮应在 onActionStart 处理过，此处不再挂起
        List<HITLTask> batchPending = trace.getContext().getAs(HITL.PENDING_TASKS);
        if (batchPending != null && !batchPending.isEmpty()) {
            return;
        }

        // 已有决策：onActionStart 应已 apply；此处再幂等 apply 一次（防御）
        HITLDecision decision = resolveDecision(trace, toolExchanger.getCallId(), toolExchanger.getToolName(), null);
        if (decision != null) {
            applyDecision(trace, toolExchanger, decision, 1);
            return;
        }

        String comment = strategy.evaluate(trace, toolExchanger.getArgs());
        if (comment == null) {
            return;
        }

        // Fallback：单工具挂起（旧路径 / 异常路径）
        HITLTask task = new HITLTask(toolExchanger.getCallId(), toolExchanger.getToolName(), toolExchanger.getArgs(), comment);
        List<HITLTask> single = new ArrayList<>(1);
        single.add(task);
        suspendBatch(trace, single);
    }

    @Override
    public void onToolCallEnd(ReActTrace trace, ToolExchanger toolExchanger,
                              @Nullable ChatMessage observation,
                              @Nullable Throwable error, long durationMs) {
        HITLDecision decision = resolveDecision(trace, toolExchanger.getCallId(), toolExchanger.getToolName(), null);

        // 尚无决策时可能处于挂起路径：保留 PENDING_TASKS / LAST_INTERVENED
        if (decision == null) {
            return;
        }

        // 成功时：注入人工备注
        if (error == null && decision.isApproved()) {
            if (Assert.isNotEmpty(decision.getComment())) {
                String base = toolExchanger.getResult() == null ? "" : toolExchanger.getResult();
                if (!base.contains("(Note: ")) {
                    toolExchanger.setResult(base + " (Note: " + decision.getComment() + ")");
                }
            }
        }

        // 清理本 call 的决策键
        if (Assert.isNotEmpty(toolExchanger.getCallId())) {
            trace.getContext().remove(HITL.DECISION_PREFIX + toolExchanger.getCallId());
        }
        if (Assert.isNotEmpty(toolExchanger.getToolName())) {
            trace.getContext().remove(HITL.DECISION_PREFIX + toolExchanger.getToolName());
        }
        trace.getContext().remove(HITL.LAST_INTERVENED);
    }

    @Override
    public void onActionEnd(ReActTrace trace) {
        // 挂起中：保留 PENDING_TASKS 供业务 getPendingTasks
        if (trace.getSession() != null && trace.getSession().isPending()) {
            return;
        }

        // 正常跑完 / 决策后执行完 / reject→END：清理批挂起与残留决策
        List<HITLTask> tasks = trace.getContext().getAs(HITL.PENDING_TASKS);
        if (tasks != null) {
            for (HITLTask t : tasks) {
                if (Assert.isNotEmpty(t.getCallUuid())) {
                    trace.getContext().remove(HITL.DECISION_PREFIX + t.getCallUuid());
                }
                if (Assert.isNotEmpty(t.getToolName())) {
                    trace.getContext().remove(HITL.DECISION_PREFIX + t.getToolName());
                }
            }
        }
        HITLTask last = trace.getContext().getAs(HITL.LAST_INTERVENED);
        if (last != null) {
            if (Assert.isNotEmpty(last.getCallUuid())) {
                trace.getContext().remove(HITL.DECISION_PREFIX + last.getCallUuid());
            }
            if (Assert.isNotEmpty(last.getToolName())) {
                trace.getContext().remove(HITL.DECISION_PREFIX + last.getToolName());
            }
        }
        trace.getContext().remove(HITL.PENDING_TASKS);
        trace.getContext().remove(HITL.LAST_INTERVENED);
    }

    /**
     * 设置批准回调（用于 alwaysAllow 场景，自动注入会话级规则）
     *
     * @param callback 回调函数，参数为 (toolName, args)
     */
    public HITLInterceptor onApproved(BiConsumer<String, Map<String, Object>> callback) {
        this.approvedCallback = callback;
        return this;
    }

    // ---------- internal ----------

    /** 清理批挂起与决策键（reject END 等不经 onToolCallEnd 的路径） */
    private void clearAllHitlState(ReActTrace trace) {
        List<HITLTask> tasks = trace.getContext().getAs(HITL.PENDING_TASKS);
        if (tasks != null) {
            for (HITLTask t : tasks) {
                if (Assert.isNotEmpty(t.getCallUuid())) {
                    trace.getContext().remove(HITL.DECISION_PREFIX + t.getCallUuid());
                }
                if (Assert.isNotEmpty(t.getToolName())) {
                    trace.getContext().remove(HITL.DECISION_PREFIX + t.getToolName());
                }
            }
        }
        HITLTask last = trace.getContext().getAs(HITL.LAST_INTERVENED);
        if (last != null) {
            if (Assert.isNotEmpty(last.getCallUuid())) {
                trace.getContext().remove(HITL.DECISION_PREFIX + last.getCallUuid());
            }
            if (Assert.isNotEmpty(last.getToolName())) {
                trace.getContext().remove(HITL.DECISION_PREFIX + last.getToolName());
            }
        }
        trace.getContext().remove(HITL.PENDING_TASKS);
        trace.getContext().remove(HITL.LAST_INTERVENED);
    }
    
    private void suspendBatch(ReActTrace trace, List<HITLTask> pending) {
        // 有未决审批时不应保持 END（以 pending 为准）
        if (Agent.ID_END.equals(trace.getRoute())) {
            trace.setRoute(ReActAgent.ID_REASON);
        }

        trace.getContext().put(HITL.PENDING_TASKS, pending);
        trace.getContext().put(HITL.LAST_INTERVENED, pending.get(0));

        String summary = pending.size() == 1
                ? pending.get(0).getComment()
                : ("有 " + pending.size() + " 项操作待人工确认");
        if (trace.getSession() != null) {
            trace.getSession().pending(true, summary);
        }
        trace.setFinalAnswer(summary);

        if (trace.hasStreamSink()) {
            for (HITLTask t : pending) {
                String callId = Assert.isNotEmpty(t.getCallUuid()) ? t.getCallUuid() : null;
                trace.pushAgentChunk(new HITLPendingChunk(trace, callId, t));
            }
        }
    }

    private HITLDecision resolveDecision(ReActTrace trace, String callUuid, String toolName,
                                         Map<String, Long> nameCount) {
        if (Assert.isNotEmpty(callUuid)) {
            HITLDecision d = trace.getContext().getAs(HITL.DECISION_PREFIX + callUuid);
            if (d != null) {
                return d;
            }
        }
        if (Assert.isNotEmpty(toolName)) {
            // 兼容：toolName 键；批内同名多实例时不读 toolName（避免撞车）
            if (nameCount != null && nameCount.getOrDefault(toolName, 0L) > 1) {
                return null;
            }
            return trace.getContext().getAs(HITL.DECISION_PREFIX + toolName);
        }
        return null;
    }

    private void applyDecision(ReActTrace trace, ToolExchanger ex, HITLDecision decision, int hitlTargetCount) {
        // 清理挂起标识（决策已到）
        trace.getContext().remove(HITL.LAST_INTERVENED);
        // decision 留到 onToolCallEnd 再删

        if (decision.isApproved()) {
            if (decision.getModifiedArgs() != null) {
                ex.getArgs().putAll(decision.getModifiedArgs());
            }
            if (decision.isAlwaysAllow() && approvedCallback != null) {
                approvedCallback.accept(ex.getToolName(), ex.getArgs());
            }
        } else if (decision.isSkipped()) {
            String msg = decision.getCommentOrDefault("操作跳过：请继续下一步。");
            ex.setResult(msg);
        } else {
            // REJECT
            String msg = decision.getCommentOrDefault("操作拒绝：人工审批未通过。");
            if (hitlTargetCount <= 1) {
                // 单敏感工具：兼容旧语义，整 run END（不进 doAction，立即清全部 HITL 状态）
                trace.setFinalAnswer(msg);
                trace.setRoute(Agent.ID_END);
                clearAllHitlState(trace);
            } else {
                // 批内多敏感：写拒绝 observation，不整批 END
                ex.setResult(msg);
            }
        }

        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new HITLDecidedChunk(trace,
                    ex.getCallId(),
                    ex.getToolName(),
                    ex.getArgs(),
                    decision));
        }
    }

    public static class HITLSensitiveStrategy implements HITLStrategy {
        private String comment;

        /**
         * 设置拦截理由文案
         */
        public HITLSensitiveStrategy comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public String evaluate(ReActTrace trace, Map<String, Object> args) {
            if (Assert.isEmpty(comment)) {
                return "敏感操作，需要人工介入确认";
            } else {
                return comment;
            }
        }
    }

    /**
     * 介入判定策略接口
     *
     * @deprecated 4.0.4 {@link HITLStrategy}
     */
    @Deprecated
    @FunctionalInterface
    public interface InterventionStrategy extends HITLStrategy {
        /**
         * 评估是否需要干预
         *
         * @return 拦截理由文案（触发拦截）；null（不拦截，直接执行）
         */
        String evaluate(ReActTrace trace, Map<String, Object> args);
    }
}
