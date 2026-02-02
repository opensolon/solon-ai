package org.noear.solon.ai.agent.react.intercept;


import org.noear.solon.ai.agent.AgentSession;

import java.util.Map;

/**
 * 提升用户体验的 HITL 操作助手
 */
public class HITL {
    public static final String KEY_PREFIX = "_hitl_approved_";
    public static final String ARGS_PREFIX = "_modified_args_";
    public static final String LAST_TOOL_KEY = "_last_intervened_tool_";
    public static final String LAST_ARGS_KEY = "_last_intervened_args_";

    public static void approve(AgentSession session, String toolName) {
        session.getSnapshot().put(KEY_PREFIX + toolName, true);
    }

    public static void approveWithModifiedArgs(AgentSession session, String toolName, Map<String, Object> newArgs) {
        session.getSnapshot().put(KEY_PREFIX + toolName, true);
        session.getSnapshot().put(ARGS_PREFIX + toolName, newArgs);
    }

    public static HITLTask getPendingTask(AgentSession session) {
        String toolName = session.getSnapshot().getAs(LAST_TOOL_KEY);
        if (toolName == null) {
            return null;
        }
        Map<String, Object> args = session.getSnapshot().getAs(LAST_ARGS_KEY);
        return new HITLTask(toolName, args);
    }
}