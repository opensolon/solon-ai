package org.noear.solon.ai.agent.react.intercept;


import org.noear.solon.ai.agent.AgentSession;

import java.util.Map;

/**
 * 提升用户体验的 HITL 操作助手
 */
public class HITL {
    public static final String KEY_PREFIX = "_hitl_approved_";
    public static final String REJECT_PREFIX = "_hitl_rejected_";
    public static final String ARGS_PREFIX = "_modified_args_";
    public static final String LAST_INTERVENED = "_last_intervened_";

    public static void reject(AgentSession session, String toolName) {
        session.getSnapshot().put(REJECT_PREFIX + toolName, true);
    }

    public static void approve(AgentSession session, String toolName) {
        session.getSnapshot().put(KEY_PREFIX + toolName, true);
    }

    public static void approveWithModifiedArgs(AgentSession session, String toolName, Map<String, Object> newArgs) {
        session.getSnapshot().put(KEY_PREFIX + toolName, true);
        session.getSnapshot().put(ARGS_PREFIX + toolName, newArgs);
    }

    public static HITLTask getPendingTask(AgentSession session) {
        HITLTask task = session.getSnapshot().getAs(LAST_INTERVENED);
        return task;
    }
}