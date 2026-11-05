package org.noear.solon.ai.harness.util;


import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.core.util.Assert;

/**
 *
 * @author noear 2026/8/28 created
 *
 */
public class TraceUtil {
    public static ReActTrace getCurrentTrace(AgentSession session) {
        return getCurrentTrace(session, null);
    }

    public static ReActTrace getCurrentTrace(AgentSession session, String traceKey) {
        if (session == null) {
            return null;
        }

        Object trace = null;

        //当前选中的代理
        String agentVal = SessionSelections.agentOf(session);
        if (Assert.isNotEmpty(agentVal)) {
            trace = session.getContext().get("__" + agentVal);
        }

        if (trace == null) {
            if (traceKey != null) {
                trace = session.getContext().get(traceKey);
            }
        }

        if (trace == null) {
            //主代理
            trace = session.getContext().get(TraceKeys.TRACE_KEY_MAIN);
        }

        return trace instanceof ReActTrace ? (ReActTrace) trace : null;
    }

    public static void removeCurrentTrace(AgentSession session) {
        removeCurrentTrace(session, null);
    }

    public static void removeCurrentTrace(AgentSession session, String traceKey) {
        if (session == null) {
            return;
        }

        //当前选中的代理
        String agentVal = SessionSelections.agentOf(session);
        if (Assert.isNotEmpty(agentVal)) {
            session.getContext().remove("__" + agentVal);
        }

        if (traceKey != null) {
            session.getContext().remove(traceKey);
        }

        //主代理
        session.getContext().remove(TraceKeys.TRACE_KEY_MAIN);
    }
}