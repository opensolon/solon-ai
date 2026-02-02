package org.noear.solon.ai.agent.react.intercept;

/**
 *
 * @author noear 2026/2/3 created
 *
 */

import org.noear.solon.ai.agent.AgentSession;

import java.util.HashMap;
import java.util.Map;

/**
 * 提升用户体验的 HITL 操作助手
 */
public class HITL {
    private static final String KEY_PREFIX = "_hitl_approved_";
    private static final String ARGS_PREFIX = "_modified_args_";

    /**
     * 批准特定的工具执行
     */
    public static void approve(AgentSession session, String toolName) {
        session.getSnapshot().put(KEY_PREFIX + toolName, true);
    }

    /**
     * 批准并修改参数后执行
     */
    public static void approveWithModifiedArgs(AgentSession session, String toolName, Map<String, Object> newArgs) {
        session.getSnapshot().put(KEY_PREFIX + toolName, true);
        session.getSnapshot().put(ARGS_PREFIX + toolName, newArgs);
    }

    /**
     * 获取当前挂起的任务信息（供前端渲染）
     */
    public static HITLTask getPendingTask(AgentSession session) {
        String toolName = session.getSnapshot().getAs("_last_intervened_tool_");
        Map<String, Object> args = session.getSnapshot().getAs("_last_intervened_args_");

        return new HITLTask(toolName, args);
    }
}