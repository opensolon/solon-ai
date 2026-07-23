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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HITL (Human-in-the-Loop) 交互助手
 *
 * <p>提供面向业务层（如 Controller 或 Service）的便捷 API。推荐用法：</p>
 * <ol>
 * <li><b>定位任务</b>：{@link #getPendingTasks} / {@link #getPendingTask} /
 * {@link #getPendingTaskByCallUuid} / {@link #getPendingTaskByToolName}</li>
 * <li><b>提交决策</b>：{@link #submit(AgentSession, HITLTask, HITLDecision)} 或
 * {@code approve/reject/skip(session, task, ...)}</li>
 * </ol>
 *
 * <p>批 HITL 场景下，决策主键仅为 {@code callUuid}（= ToolCall.uuid / 文本模式稳定 callId）。
 * toolName 仅用于定位任务（{@link #getPendingTaskByToolName}），不再作为决策存储键。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITL {
    /**
     * 决策状态存储前缀（后接 callUuid）
     */
    public static final String DECISION_PREFIX = "_hitl_decision_";
    /**
     * 最近一次被拦截的任务存储 Key（兼容：批挂起时指向第一个）
     */
    public static final String LAST_INTERVENED = "_last_intervened_";
    /**
     * 本轮待审批任务列表
     */
    public static final String PENDING_TASKS = "_hitl_pending_tasks_";

    // ---------- 查询 ----------

    /**
     * 获取本轮全部挂起任务（批 HITL）
     *
     * @return 挂起任务列表；无则 emptyList（非 null）
     */
    public static List<HITLTask> getPendingTasks(AgentSession session) {
        List<HITLTask> tasks = session.getContext().getAs(PENDING_TASKS);
        if (tasks == null || tasks.isEmpty()) {
            HITLTask single = session.getContext().getAs(LAST_INTERVENED);
            if (single != null) {
                return Collections.singletonList(single);
            }
            return Collections.emptyList();
        }
        // 返回只读视图，避免业务 clear/add 污染 session 内列表
        return Collections.unmodifiableList(tasks);
    }

    /**
     * 获取会话中当前挂起的任务信息（兼容：返回批中第一个）
     *
     * @return 挂起的任务实体，若无挂起则返回 null
     */
    public static HITLTask getPendingTask(AgentSession session) {
        List<HITLTask> tasks = session.getContext().getAs(PENDING_TASKS);
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0);
        }
        return session.getContext().getAs(LAST_INTERVENED);
    }

    /**
     * 是否存在挂起的 HITL 任务
     */
    public static boolean isHitl(AgentSession session) {
        return getPendingTask(session) != null;
    }

    /**
     * 按 callUuid 获取挂起任务
     *
     * @return 命中则返回任务，否则 null
     */
    public static HITLTask getPendingTaskByCallUuid(AgentSession session, String callUuid) {
        if (Assert.isEmpty(callUuid)) {
            return null;
        }
        for (HITLTask t : getPendingTasks(session)) {
            if (callUuid.equals(t.getCallUuid())) {
                return t;
            }
        }
        return null;
    }

    /**
     * 按 toolName 获取挂起任务（批内该名须唯一）
     *
     * @return 唯一命中则返回任务；无命中返回 null
     * @throws IllegalStateException 批内同名多实例
     */
    public static HITLTask getPendingTaskByToolName(AgentSession session, String toolName) {
        if (Assert.isEmpty(toolName)) {
            return null;
        }
        HITLTask unique = null;
        int count = 0;
        for (HITLTask t : getPendingTasks(session)) {
            if (toolName.equals(t.getToolName())) {
                count++;
                unique = t;
            }
        }
        if (count > 1) {
            throw new IllegalStateException(
                    "Multiple pending HITL tasks share toolName='" + toolName
                            + "'; use getPendingTaskByCallUuid(callUuid) instead");
        }
        return unique;
    }

    /**
     * 按 toolName 获取全部挂起任务（同名多实例场景）
     *
     * @return 匹配列表；无则 emptyList
     */
    public static List<HITLTask> getPendingTasksByToolName(AgentSession session, String toolName) {
        if (Assert.isEmpty(toolName)) {
            return Collections.emptyList();
        }
        List<HITLTask> matched = new ArrayList<>();
        for (HITLTask t : getPendingTasks(session)) {
            if (toolName.equals(t.getToolName())) {
                matched.add(t);
            }
        }
        return matched.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(matched);
    }

    /**
     * 获取指定任务的决策（按 callUuid）
     */
    public static HITLDecision getDecision(AgentSession session, HITLTask task) {
        if (task == null || Assert.isEmpty(task.getCallUuid())) {
            return null;
        }
        return session.getContext().getAs(DECISION_PREFIX + task.getCallUuid());
    }

    /**
     * 按 callUuid 获取决策
     */
    public static HITLDecision getDecision(AgentSession session, String callUuid) {
        if (Assert.isEmpty(callUuid)) {
            return null;
        }
        return session.getContext().getAs(DECISION_PREFIX + callUuid);
    }

    // ---------- 决策（主路径：面向 HITLTask） ----------

    /**
     * 提交人工决策（主路径）
     * <p>仅按 callUuid 写入决策键。无 callUuid 时，若 pending 中 toolName 唯一，
     * 则解析到对应任务的 callUuid 后再写入。</p>
     */
    public static void submit(AgentSession session, HITLTask task, HITLDecision decision) {
        if (task == null) {
            throw new IllegalArgumentException("task is null");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision is null");
        }

        String callUuid = task.getCallUuid();
        if (Assert.isEmpty(callUuid) && Assert.isNotEmpty(task.getToolName())) {
            // 无 callUuid 的极老 task：按 toolName 唯一定位后写其 callUuid
            HITLTask unique = getPendingTaskByToolName(session, task.getToolName());
            if (unique != null) {
                callUuid = unique.getCallUuid();
            }
        }
        if (Assert.isEmpty(callUuid)) {
            throw new IllegalArgumentException("task.callUuid is empty (toolName-only decision key is no longer supported)");
        }

        session.getContext().put(DECISION_PREFIX + callUuid, decision);
    }

    /**
     * 快捷批准
     */
    public static void approve(AgentSession session, HITLTask task) {
        submit(session, task, HITLDecision.approve());
    }

    /**
     * 快捷批准（带 alwaysAllow）
     */
    public static void approve(AgentSession session, HITLTask task, boolean alwaysAllow) {
        submit(session, task, HITLDecision.approve(alwaysAllow));
    }

    /**
     * 快捷批准（带备注）
     */
    public static void approve(AgentSession session, HITLTask task, String comment) {
        submit(session, task, HITLDecision.approve().comment(comment));
    }

    /**
     * 快捷拒绝
     */
    public static void reject(AgentSession session, HITLTask task) {
        submit(session, task, HITLDecision.reject(null));
    }

    /**
     * 快捷拒绝（带理由）
     */
    public static void reject(AgentSession session, HITLTask task, String comment) {
        submit(session, task, HITLDecision.reject(comment));
    }

    /**
     * 快捷跳过
     */
    public static void skip(AgentSession session, HITLTask task) {
        submit(session, task, HITLDecision.skip(null));
    }

    /**
     * 快捷跳过（带原因）
     */
    public static void skip(AgentSession session, HITLTask task, String comment) {
        submit(session, task, HITLDecision.skip(comment));
    }

    /**
     * 批量提交决策（key = callUuid）
     */
    public static void submitAll(AgentSession session, Map<String, HITLDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, HITLDecision> e : decisions.entrySet()) {
            String callUuid = e.getKey();
            HITLDecision decision = e.getValue();
            HITLTask task = getPendingTaskByCallUuid(session, callUuid);
            if (task != null) {
                submit(session, task, decision);
            } else if (Assert.isNotEmpty(callUuid)) {
                // 宽松：允许直接按 callUuid 写键（任务列表尚未同步等）
                session.getContext().put(DECISION_PREFIX + callUuid, decision);
            } else {
                throw new IllegalArgumentException("callUuid is empty");
            }
        }
    }

    /**
     * 批准当前全部挂起任务（原参）
     */
    public static void approveAll(AgentSession session) {
        for (HITLTask task : getPendingTasks(session)) {
            approve(session, task);
        }
    }

    /**
     * 拒绝当前全部挂起任务
     */
    public static void rejectAll(AgentSession session, String comment) {
        for (HITLTask task : getPendingTasks(session)) {
            reject(session, task, comment);
        }
    }

    // ---------- 兼容：按 toolName 决策 ----------

    /**
     * 按 toolName 提交决策（批内该名须唯一）
     *
     * @deprecated 4.0.4 使用 {@link #getPendingTaskByToolName} + {@link #submit(AgentSession, HITLTask, HITLDecision)}
     *             或直接 {@link #submit(AgentSession, HITLTask, HITLDecision)}
     */
    @Deprecated
    public static void submit(AgentSession session, String toolName, HITLDecision decision) {
        if (Assert.isEmpty(toolName)) {
            throw new IllegalArgumentException("toolName is empty");
        }
        HITLTask task = getPendingTaskByToolName(session, toolName);
        if (task == null) {
            throw new IllegalStateException(
                    "No pending HITL task for toolName='" + toolName
                            + "'; use getPendingTaskByCallUuid + submit(task) instead");
        }
        submit(session, task, decision);
    }

    /**
     * @deprecated 4.0.4 使用 {@link #approve(AgentSession, HITLTask)}
     */
    @Deprecated
    public static void approve(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.approve());
    }

    /**
     * @deprecated 4.0.4 使用 {@link #approve(AgentSession, HITLTask, boolean)}
     */
    @Deprecated
    public static void approve(AgentSession session, String toolName, boolean alwaysAllow) {
        submit(session, toolName, HITLDecision.approve(alwaysAllow));
    }

    /**
     * @deprecated 4.0.4 使用 {@link #approve(AgentSession, HITLTask, String)}
     */
    @Deprecated
    public static void approve(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.approve().comment(comment));
    }

    /**
     * @deprecated 4.0.4 使用 {@link #reject(AgentSession, HITLTask)}
     */
    @Deprecated
    public static void reject(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.reject(null));
    }

    /**
     * @deprecated 4.0.4 使用 {@link #reject(AgentSession, HITLTask, String)}
     */
    @Deprecated
    public static void reject(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.reject(comment));
    }

    /**
     * @deprecated 4.0.4 使用 {@link #skip(AgentSession, HITLTask)}
     */
    @Deprecated
    public static void skip(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.skip(null));
    }

    /**
     * @deprecated 4.0.4 使用 {@link #skip(AgentSession, HITLTask, String)}
     */
    @Deprecated
    public static void skip(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.skip(comment));
    }

    // ---------- 清理 ----------

    /**
     * 清理指定任务相关状态
     */
    public static void clear(AgentSession session, HITLTask task) {
        if (task == null) {
            return;
        }
        if (Assert.isNotEmpty(task.getCallUuid())) {
            session.getContext().remove(DECISION_PREFIX + task.getCallUuid());
        }

        HITLTask last = session.getContext().getAs(LAST_INTERVENED);
        if (last != null && isSameTask(last, task)) {
            session.getContext().remove(LAST_INTERVENED);
        }

        List<HITLTask> tasks = session.getContext().getAs(PENDING_TASKS);
        if (tasks != null) {
            tasks.removeIf(t -> isSameTask(t, task));
            if (tasks.isEmpty()) {
                session.getContext().remove(PENDING_TASKS);
            }
        }
    }

    /**
     * 清理全部 HITL 挂起与决策状态（本批）
     */
    public static void clear(AgentSession session) {
        List<HITLTask> tasks = getPendingTasks(session);
        for (HITLTask task : tasks) {
            if (Assert.isNotEmpty(task.getCallUuid())) {
                session.getContext().remove(DECISION_PREFIX + task.getCallUuid());
            }
        }
        session.getContext().remove(LAST_INTERVENED);
        session.getContext().remove(PENDING_TASKS);
    }

    // ---------- internal ----------

    private static boolean isSameTask(HITLTask a, HITLTask b) {
        if (a == b) {
            return true;
        }
        if (Assert.isNotEmpty(a.getCallUuid()) && Assert.isNotEmpty(b.getCallUuid())) {
            return a.getCallUuid().equals(b.getCallUuid());
        }
        return a.getToolName() != null && a.getToolName().equals(b.getToolName());
    }
}
