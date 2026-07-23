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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HITL (Human-in-the-Loop) 交互助手
 *
 * <p>提供面向业务层（如 Controller 或 Service）的便捷 API。主要用于：</p>
 * <ul>
 * <li><b>任务探知</b>：通过 {@link #getPendingTasks} / {@link #getPendingTask} 获取挂起任务。</li>
 * <li><b>决策回填</b>：通过 {@code approve/reject/skip}（推荐按 callUuid）提交人工干预指令，驱动 Agent 恢复执行。</li>
 * </ul>
 *
 * <p>批 HITL 场景下，决策主键为 {@code callUuid}（= ToolCall.uuid）。
 * 旧的 toolName API 在批内该工具名唯一时仍可用。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITL {
    /**
     * 决策状态存储前缀（后接 callUuid 或 toolName）
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

    public static boolean isHitl(AgentSession session) {
        return getPendingTask(session) != null;
    }

    public static HITLDecision getDecision(AgentSession session, HITLTask task) {
        if (task == null) {
            return null;
        }
        if (Assert.isNotEmpty(task.getCallUuid())) {
            HITLDecision d = session.getContext().getAs(DECISION_PREFIX + task.getCallUuid());
            if (d != null) {
                return d;
            }
        }
        return session.getContext().getAs(DECISION_PREFIX + task.getToolName());
    }

    public static HITLDecision getDecision(AgentSession session, String key) {
        return session.getContext().getAs(DECISION_PREFIX + key);
    }

    // ---------- 按 callUuid 决策（批 HITL 主路径） ----------

    /**
     * 按 callUuid 提交人工决策
     */
    public static void submitByCallUuid(AgentSession session, String callUuid, HITLDecision decision) {
        if (Assert.isEmpty(callUuid)) {
            throw new IllegalArgumentException("callUuid is empty");
        }
        session.getContext().put(DECISION_PREFIX + callUuid, decision);

        // 若批内该 callUuid 对应工具名唯一，双写 toolName 兼容键，便于文本 resume 等场景
        HITLTask matched = findPendingByCallUuid(session, callUuid);
        if (matched != null && Assert.isNotEmpty(matched.getToolName())
                && countPendingByToolName(session, matched.getToolName()) == 1) {
            session.getContext().put(DECISION_PREFIX + matched.getToolName(), decision);
        }
    }

    public static void approveByCallUuid(AgentSession session, String callUuid) {
        submitByCallUuid(session, callUuid, HITLDecision.approve());
    }

    public static void approveByCallUuid(AgentSession session, String callUuid, boolean alwaysAllow) {
        submitByCallUuid(session, callUuid, HITLDecision.approve(alwaysAllow));
    }

    public static void approveByCallUuid(AgentSession session, String callUuid, String comment) {
        submitByCallUuid(session, callUuid, HITLDecision.approve().comment(comment));
    }

    public static void rejectByCallUuid(AgentSession session, String callUuid) {
        submitByCallUuid(session, callUuid, HITLDecision.reject(null));
    }

    public static void rejectByCallUuid(AgentSession session, String callUuid, String comment) {
        submitByCallUuid(session, callUuid, HITLDecision.reject(comment));
    }

    public static void skipByCallUuid(AgentSession session, String callUuid) {
        submitByCallUuid(session, callUuid, HITLDecision.skip(null));
    }

    public static void skipByCallUuid(AgentSession session, String callUuid, String comment) {
        submitByCallUuid(session, callUuid, HITLDecision.skip(comment));
    }

    /**
     * 批量提交决策（key = callUuid）
     */
    public static void submitAll(AgentSession session, Map<String, HITLDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, HITLDecision> e : decisions.entrySet()) {
            submitByCallUuid(session, e.getKey(), e.getValue());
        }
    }

    /**
     * 批准当前全部挂起任务（原参）
     */
    public static void approveAll(AgentSession session) {
        for (HITLTask task : getPendingTasks(session)) {
            if (Assert.isNotEmpty(task.getCallUuid())) {
                approveByCallUuid(session, task.getCallUuid());
            } else if (Assert.isNotEmpty(task.getToolName())) {
                approve(session, task.getToolName());
            }
        }
    }

    /**
     * 拒绝当前全部挂起任务
     */
    public static void rejectAll(AgentSession session, String comment) {
        for (HITLTask task : getPendingTasks(session)) {
            if (Assert.isNotEmpty(task.getCallUuid())) {
                rejectByCallUuid(session, task.getCallUuid(), comment);
            } else if (Assert.isNotEmpty(task.getToolName())) {
                reject(session, task.getToolName(), comment);
            }
        }
    }

    // ---------- 按 toolName 决策（兼容旧 API；批内同名唯一时可用） ----------

    /**
     * 提交人工决策
     *
     * @param session  Agent 会话
     * @param toolName 工具名称（批内该名须唯一；多实例请用 callUuid API）
     * @param decision 决策实体（包含同意、拒绝理由或修正参数）
     */
    public static void submit(AgentSession session, String toolName, HITLDecision decision) {
        if (Assert.isEmpty(toolName)) {
            throw new IllegalArgumentException("toolName is empty");
        }
        
        List<HITLTask> tasks = getPendingTasks(session);
        if (!tasks.isEmpty()) {
            HITLTask unique = null;
            int count = 0;
            for (HITLTask t : tasks) {
                if (toolName.equals(t.getToolName())) {
                    count++;
                    unique = t;
                }
            }
            if (count > 1) {
                throw new IllegalStateException(
                        "Multiple pending HITL tasks share toolName='" + toolName
                                + "'; use submitByCallUuid(callUuid, decision) instead");
            }
            if (count == 1 && Assert.isNotEmpty(unique.getCallUuid())) {
                // 主写 callUuid，并双写 toolName
                session.getContext().put(DECISION_PREFIX + unique.getCallUuid(), decision);
            }
        }

        session.getContext().put(DECISION_PREFIX + toolName, decision);
    }

    /**
     * 快捷批准工具执行
     */
    public static void approve(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.approve());
    }

    /**
     * 快捷批准工具执行（带 alwaysAllow 标志）
     *
     * @param alwaysAllow true 表示后续同类操作自动放行，不再弹确认
     */
    public static void approve(AgentSession session, String toolName, boolean alwaysAllow) {
        submit(session, toolName, HITLDecision.approve(alwaysAllow));
    }

    /**
     * 快捷批准工具执行
     */
    public static void approve(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.approve().comment(comment));
    }

    /**
     * 快捷拒绝工具执行（使用默认拒绝意见）
     */
    public static void reject(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.reject(null));
    }

    /**
     * 快捷拒绝工具执行（带具体意见）
     */
    public static void reject(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.reject(comment));
    }

    /**
     * 快捷跳过工具执行（使用默认拒绝意见）
     */
    public static void skip(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.skip(null));
    }

    /**
     * 快捷跳过工具执行（带具体意见）
     */
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
        if (Assert.isNotEmpty(task.getToolName())) {
            session.getContext().remove(DECISION_PREFIX + task.getToolName());
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
            if (Assert.isNotEmpty(task.getToolName())) {
                session.getContext().remove(DECISION_PREFIX + task.getToolName());
            }
        }
        session.getContext().remove(LAST_INTERVENED);
        session.getContext().remove(PENDING_TASKS);
    }

    // ---------- internal ----------

    private static HITLTask findPendingByCallUuid(AgentSession session, String callUuid) {
        for (HITLTask t : getPendingTasks(session)) {
            if (callUuid.equals(t.getCallUuid())) {
                return t;
            }
        }
        return null;
    }

    private static int countPendingByToolName(AgentSession session, String toolName) {
        int n = 0;
        for (HITLTask t : getPendingTasks(session)) {
            if (toolName.equals(t.getToolName())) {
                n++;
            }
        }
        return n;
    }

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
