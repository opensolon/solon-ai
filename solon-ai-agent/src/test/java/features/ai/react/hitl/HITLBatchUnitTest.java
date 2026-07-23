package features.ai.react.hitl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 批 HITL 纯单测：不依赖真实 LLM，直接驱动 HITLInterceptor 生命周期。
 */
public class HITLBatchUnitTest {

    @Test
    public void batchPendingCollectsAllSensitiveTools() {
        HITLInterceptor hitl = new HITLInterceptor()
                .onSensitiveTool("transfer", "delete");

        AgentSession session = InMemoryAgentSession.of("batch_1");
        ReActTrace trace = newTrace(session);

        List<ToolExchanger> batch = Arrays.asList(
                new ToolExchanger("u1", "search", mapOf("q", "x")),
                new ToolExchanger("u2", "transfer", mapOf("to", "A", "amount", 100)),
                new ToolExchanger("u3", "delete", mapOf("id", "9"))
        );

        hitl.onActionStart(trace, batch);

        Assertions.assertTrue(session.isPending(), "应整批挂起");
        List<HITLTask> tasks = HITL.getPendingTasks(session);
        Assertions.assertEquals(2, tasks.size(), "仅敏感工具进入 pending");
        Assertions.assertEquals("u2", tasks.get(0).getCallUuid());
        Assertions.assertEquals("u3", tasks.get(1).getCallUuid());
        Assertions.assertEquals("transfer", tasks.get(0).getToolName());
        Assertions.assertEquals("delete", tasks.get(1).getToolName());

        // 安全工具未改 result
        Assertions.assertNull(batch.get(0).getResult());
    }

    @Test
    public void sameToolNameTwoCallsUseCallUuid() {
        HITLInterceptor hitl = new HITLInterceptor().onSensitiveTool("transfer");
        AgentSession session = InMemoryAgentSession.of("batch_2");
        ReActTrace trace = newTrace(session);

        List<ToolExchanger> batch = Arrays.asList(
                new ToolExchanger("uuid-a", "transfer", mapOf("to", "A", "amount", 1)),
                new ToolExchanger("uuid-b", "transfer", mapOf("to", "B", "amount", 2))
        );

        hitl.onActionStart(trace, batch);
        Assertions.assertTrue(session.isPending());
        List<HITLTask> tasks = HITL.getPendingTasks(session);
        Assertions.assertEquals(2, tasks.size());

        // toolName API 在同名多实例时应失败
        try {
            HITL.approve(session, "transfer");
            Assertions.fail("同名多实例应禁止 toolName 决策");
        } catch (IllegalStateException expected) {
            // ok
        }

        HITL.approveByCallUuid(session, "uuid-a");
        HITL.rejectByCallUuid(session, "uuid-b", "拒 B");

        // 模拟 resume：清 pending 标志（与 ReActTrace.prepare 一致）
        session.pending(false, null);
        ReActTrace trace2 = newTrace(session);
        List<ToolExchanger> batch2 = Arrays.asList(
                new ToolExchanger("uuid-a", "transfer", mapOf("to", "A", "amount", 1)),
                new ToolExchanger("uuid-b", "transfer", mapOf("to", "B", "amount", 2))
        );
        hitl.onActionStart(trace2, batch2);

        Assertions.assertFalse(session.isPending(), "决策齐后不应再挂起");
        Assertions.assertNull(batch2.get(0).getResult(), "approve 不写 result");
        Assertions.assertNotNull(batch2.get(1).getResult(), "reject 在多 call 时写 result");
        Assertions.assertTrue(batch2.get(1).getResult().contains("拒 B"));
    }

    @Test
    public void singleRejectStillEndsRun() {
        HITLInterceptor hitl = new HITLInterceptor().onSensitiveTool("transfer");
        AgentSession session = InMemoryAgentSession.of("batch_3");
        ReActTrace trace = newTrace(session);

        List<ToolExchanger> batch = Collections.singletonList(
                new ToolExchanger("only-1", "transfer", mapOf("to", "A", "amount", 9))
        );
        hitl.onActionStart(trace, batch);
        Assertions.assertTrue(session.isPending());

        HITL.reject(session, "transfer", "管理员拒绝");
        session.pending(false, null);

        ReActTrace trace2 = newTrace(session);
        List<ToolExchanger> batch2 = Collections.singletonList(
                new ToolExchanger("only-1", "transfer", mapOf("to", "A", "amount", 9))
        );
        hitl.onActionStart(trace2, batch2);

        Assertions.assertEquals(Agent.ID_END, trace2.getRoute());
        Assertions.assertTrue(trace2.getFinalAnswer().contains("管理员拒绝"));
    }

    @Test
    public void approveAllAndSubmitAll() {
        AgentSession session = InMemoryAgentSession.of("batch_4");
        session.getContext().put(HITL.PENDING_TASKS, Arrays.asList(
                new HITLTask("c1", "transfer", mapOf("a", 1), "need"),
                new HITLTask("c2", "delete", mapOf("b", 2), "need")
        ));

        HITL.approveAll(session);
        Assertions.assertNotNull(HITL.getDecision(session, "c1"));
        Assertions.assertNotNull(HITL.getDecision(session, "c2"));
        Assertions.assertTrue(HITL.getDecision(session, "c1").isApproved());

        HITL.clear(session);
        session.getContext().put(HITL.PENDING_TASKS, Arrays.asList(
                new HITLTask("c1", "transfer", mapOf("a", 1), "need"),
                new HITLTask("c2", "delete", mapOf("b", 2), "need")
        ));
        Map<String, HITLDecision> map = new LinkedHashMap<>();
        map.put("c1", HITLDecision.skip("s1"));
        map.put("c2", HITLDecision.reject("r2"));
        HITL.submitAll(session, map);
        Assertions.assertTrue(HITL.getDecision(session, "c1").isSkipped());
        Assertions.assertTrue(HITL.getDecision(session, "c2").isRejected());
    }

    private static ReActTrace newTrace(AgentSession session) {
        ReActTrace trace = new ReActTrace();
        try {
            Field f = ReActTrace.class.getDeclaredField("session");
            f.setAccessible(true);
            f.set(trace, session);
            return trace;
        } catch (Exception e) {
            throw new IllegalStateException("inject session into ReActTrace failed", e);
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
