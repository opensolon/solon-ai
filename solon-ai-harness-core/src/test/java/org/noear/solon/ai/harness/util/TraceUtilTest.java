package org.noear.solon.ai.harness.util;

import org.noear.solon.ai.harness.session.SessionSelections;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * TraceUtil 三级回退链单元测试：选中代理 → 显式 traceKey → __main。
 * ContinueCommand/InterruptCommand 均依赖此链，此前零覆盖。
 */
class TraceUtilTest {

    @Test
    void nullSessionReturnsNull() {
        assertNull(TraceUtil.getCurrentTrace(null));
        assertNull(TraceUtil.getCurrentTrace(null, "__x"));
        // remove 系列对 null 会话静默
        TraceUtil.removeCurrentTrace(null);
        TraceUtil.removeCurrentTrace(null, "__x");
    }

    @Test
    void selectedAgentTraceWinsOverMain() {
        AgentSession session = InMemoryAgentSession.of();
        ReActTrace mainTrace = new ReActTrace();
        ReActTrace agentTrace = new ReActTrace();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, mainTrace);
        SessionSelections.setAgent(session, "frontend");
        session.getContext().put("__frontend", agentTrace);

        assertSame(agentTrace, TraceUtil.getCurrentTrace(session));
    }

    @Test
    void selectedAgentMissingFallsBackToExplicitKeyThenMain() {
        AgentSession session = InMemoryAgentSession.of();
        ReActTrace mainTrace = new ReActTrace();
        ReActTrace keyTrace = new ReActTrace();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, mainTrace);
        session.getContext().put("__bash", keyTrace);
        // 选中代理 "code" 无对应 trace（未运行过）

        assertSame(keyTrace, TraceUtil.getCurrentTrace(session, "__bash"));
        // 显式 key 也无时 → __main
        assertSame(mainTrace, TraceUtil.getCurrentTrace(session, "__none"));
        assertSame(mainTrace, TraceUtil.getCurrentTrace(session));
    }

    @Test
    void nonTraceValueIsIgnoredNotCast() {
        // 上下文里塞了非 ReActTrace 对象：安全忽略返回 null，不 ClassCastException
        AgentSession session = InMemoryAgentSession.of();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, "不是 trace");

        assertNull(TraceUtil.getCurrentTrace(session));
    }

    @Test
    void removeCurrentTraceClearsAllThreeSlots() {
        AgentSession session = InMemoryAgentSession.of();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, new ReActTrace());
        SessionSelections.setAgent(session, "frontend");
        session.getContext().put("__frontend", new ReActTrace());

        TraceUtil.removeCurrentTrace(session);

        assertNull(session.getContext().get(TraceKeys.TRACE_KEY_MAIN));
        assertNull(session.getContext().get("__frontend"));
        // removeCurrentTrace 不清选择本身（选择与会话绑定，仅 trace 随轮次消亡）
        assertNotNull(SessionSelections.agentOf(session));
    }

    @Test
    void removeWithExplicitKeyClearsKeyAndMain() {
        AgentSession session = InMemoryAgentSession.of();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, new ReActTrace());
        session.getContext().put("__bash", new ReActTrace());

        TraceUtil.removeCurrentTrace(session, "__bash");

        assertNull(session.getContext().get("__bash"));
        assertNull(session.getContext().get(TraceKeys.TRACE_KEY_MAIN));
    }

    @Test
    void traceKeysMainConstant() {
        assertEquals("__main", TraceKeys.TRACE_KEY_MAIN);
    }
}
