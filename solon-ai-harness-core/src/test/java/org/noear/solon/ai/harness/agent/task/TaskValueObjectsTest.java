package org.noear.solon.ai.harness.agent.task;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件包装与任务操作值对象、验证结果：委托取值、判据、字符串表示。
 */
class TaskValueObjectsTest {

    // ---------- TaskWrapEvent ----------

    private static AgentEvent stubEvent(AgentSession session) {
        return new AgentEvent() {
            @Override
            public String getRunId() {
                return "run-1";
            }

            @Override
            public String getAgentName() {
                return "child-agent";
            }

            @Override
            public AgentSession getSession() {
                return session;
            }

            @Override
            public String getText() {
                return "child text";
            }

            @Override
            public java.util.Map<String, Object> getMeta() {
                return java.util.Collections.emptyMap();
            }

            @Override
            public boolean hasMeta(String name) {
                return false;
            }
        };
    }

    @Test
    void wrapEventDelegatesToRealEventAndTaskOp() {
        AgentSession session = InMemoryAgentSession.of();
        TaskTalent.SingleTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = "worker";
        op.description = "do the thing";
        op.prompt = "detailed prompt";
        ((TaskTalent.MultiTaskOp) op).index = 3;

        AgentEvent real = stubEvent(session);
        TaskWrapEvent wrapped = new TaskWrapEvent(null, "task-1", (TaskTalent.MultiTaskOp) op, true, real);

        assertEquals("task-1", wrapped.getTaskId());
        assertEquals(3, wrapped.getTaskIndex());
        assertEquals("worker", wrapped.getTaskAgentName());
        assertEquals("do the thing", wrapped.getTaskDescription());
        assertTrue(wrapped.isMultitask());

        // 委托给真实事件
        assertSame(real, wrapped.getRealEvent());
        assertEquals("child text", wrapped.getText());
        assertEquals("run-1", wrapped.getRunId());
        assertEquals("child-agent", wrapped.getAgentName());
        assertSame(session, wrapped.getSession());
    }

    @Test
    void singleTaskModeHasNoMultitaskFlag() {
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "solo";
        op.description = "single";

        AgentEvent real = stubEvent(InMemoryAgentSession.of());
        TaskWrapEvent wrapped = new TaskWrapEvent(null, "task-2", null, false, real);

        assertFalse(wrapped.isMultitask());
        assertEquals("task-2", wrapped.getTaskId());
    }

    // ---------- TaskOp toString ----------

    @Test
    void singleTaskOpToStringIncludesAgentAndDescription() {
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "查找配置项";

        String s = op.toString();
        assertTrue(s.contains("explore"), s);
        assertTrue(s.contains("查找配置项"), s);
        assertTrue(s.startsWith("SingleTaskOp{"), s);
    }

    @Test
    void multiTaskOpToStringIncludesIndex() {
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = "bash";
        op.description = "运行构建";
        op.index = 7;

        String s = op.toString();
        assertTrue(s.contains("MultiTaskOp{"), s);
        assertTrue(s.contains("7"), s);
        assertTrue(s.contains("bash"), s);
    }

    @Test
    void taskOpDefaults() {
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        assertEquals(1, op.index, "index 默认 1");
    }
}
