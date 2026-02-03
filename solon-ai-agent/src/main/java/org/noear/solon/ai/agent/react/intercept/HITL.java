package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.AgentSession;

/**
 * 提升用户体验的 HITL 操作助手
 */
public class HITL {
    public static final String DECISION_PREFIX = "_hitl_decision_";
    public static final String LAST_INTERVENED = "_last_intervened_";

    /**
     * 提交决策（同意/拒绝/修正）
     */
    public static void submit(AgentSession session, String toolName, HITLDecision decision) {
        session.getSnapshot().put(DECISION_PREFIX + toolName, decision);
    }

    /**
     * 快捷批准
     */
    public static void approve(AgentSession session, String toolName) {
        submit(session, toolName, HITLDecision.approve());
    }

    /**
     * 快捷拒绝
     */
    public static void reject(AgentSession session, String toolName, String comment) {
        submit(session, toolName, HITLDecision.reject(comment));
    }

    /**
     * 获取当前挂起的任务信息
     */
    public static HITLTask getPendingTask(AgentSession session) {
        return session.getSnapshot().getAs(LAST_INTERVENED);
    }
}