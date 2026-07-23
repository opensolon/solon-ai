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
import java.util.concurrent.atomic.AtomicInteger;

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

        // toolName 查找在同名多实例时应失败
        try {
            HITL.getPendingTaskByToolName(session, "transfer");
            Assertions.fail("同名多实例应禁止 toolName 查找");
        } catch (IllegalStateException expected) {
            // ok
        }

        HITL.approve(session, HITL.getPendingTaskByCallUuid(session, "uuid-a"));
        HITL.reject(session, HITL.getPendingTaskByCallUuid(session, "uuid-b"), "拒 B");

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

        HITL.reject(session, HITL.getPendingTaskByToolName(session, "transfer"), "管理员拒绝");
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
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Arrays.asList(
                new HITLTask("c1", "transfer", mapOf("a", 1), "need"),
                new HITLTask("c2", "delete", mapOf("b", 2), "need")
        )));

        HITL.approveAll(session);
        Assertions.assertNotNull(HITL.getDecision(session, "c1"));
        Assertions.assertNotNull(HITL.getDecision(session, "c2"));
        Assertions.assertTrue(HITL.getDecision(session, "c1").isApproved());

        HITL.clear(session);
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Arrays.asList(
                new HITLTask("c1", "transfer", mapOf("a", 1), "need"),
                new HITLTask("c2", "delete", mapOf("b", 2), "need")
        )));
        Map<String, HITLDecision> map = new LinkedHashMap<>();
        map.put("c1", HITLDecision.skip("s1"));
        map.put("c2", HITLDecision.reject("r2"));
        HITL.submitAll(session, map);
        Assertions.assertTrue(HITL.getDecision(session, "c1").isSkipped());
        Assertions.assertTrue(HITL.getDecision(session, "c2").isRejected());
    }

    /**
     * 审查 P0：onActionStart 已 apply 后，onToolCallStart 不得二次 apply（hitlTargetCount=1 会把批 reject 升级为 END）。
     */
    @Test
    public void toolCallStartMustNotReapplyAfterActionStart() {
        AtomicInteger approvedCb = new AtomicInteger();
        HITLInterceptor hitl = new HITLInterceptor()
                .onSensitiveTool("transfer")
                .onApproved((name, args) -> approvedCb.incrementAndGet());

        AgentSession session = InMemoryAgentSession.of("batch_reapply");
        ReActTrace trace = newTrace(session);

        List<ToolExchanger> batch = Arrays.asList(
                new ToolExchanger("uuid-a", "transfer", mapOf("to", "A", "amount", 1)),
                new ToolExchanger("uuid-b", "transfer", mapOf("to", "B", "amount", 2))
        );

        hitl.onActionStart(trace, batch);
        Assertions.assertTrue(session.isPending());

        HITL.approve(session, HITL.getPendingTaskByCallUuid(session, "uuid-a"), true);
        HITL.reject(session, HITL.getPendingTaskByCallUuid(session, "uuid-b"), "拒 B");
        session.pending(false, null);

        ReActTrace trace2 = newTrace(session);
        List<ToolExchanger> batch2 = Arrays.asList(
                new ToolExchanger("uuid-a", "transfer", mapOf("to", "A", "amount", 1)),
                new ToolExchanger("uuid-b", "transfer", mapOf("to", "B", "amount", 2))
        );
        hitl.onActionStart(trace2, batch2);

        Assertions.assertFalse(session.isPending());
        Assertions.assertNotEquals(Agent.ID_END, trace2.getRoute(), "批内 partial reject 不应 END");
        Assertions.assertNull(batch2.get(0).getResult());
        Assertions.assertNotNull(batch2.get(1).getResult());
        Assertions.assertEquals(1, approvedCb.get(), "alwaysAllow 回调应只触发一次（ActionStart）");

        // 模拟 doAction 进入 onToolCallStart：不得二次 apply
        hitl.onToolCallStart(trace2, batch2.get(0));
        hitl.onToolCallStart(trace2, batch2.get(1));

        Assertions.assertNotEquals(Agent.ID_END, trace2.getRoute(), "二次 onToolCallStart 不得把 reject 升级为 END");
        Assertions.assertEquals(1, approvedCb.get(), "二次 onToolCallStart 不得再触发 alwaysAllow");
        Assertions.assertTrue(batch2.get(1).getResult().contains("拒 B"));
    }

    /**
     * 兼容旧 3 参构造仍可编译使用。
     */
    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedThreeArgConstructorStillWorks() {
        HITLTask task = new HITLTask("a1","transfer", mapOf("a", 1), "need");
        Assertions.assertEquals("transfer", task.getToolName());
        Assertions.assertNull(task.getCallUuid());
        Assertions.assertEquals("need", task.getComment());
    }

    /**
     * 主路径：getPendingTaskByCallUuid / getPendingTaskByToolName + submit(task)。
     */
    @Test
    public void getPendingTaskAndSubmitByTask() {
        AgentSession session = InMemoryAgentSession.of("batch_find");
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Arrays.asList(
                new HITLTask("c1", "transfer", mapOf("a", 1), "need"),
                new HITLTask("c2", "delete", mapOf("b", 2), "need"),
                new HITLTask("c3", "transfer", mapOf("a", 3), "need")
        )));

        HITLTask byUuid = HITL.getPendingTaskByCallUuid(session, "c2");
        Assertions.assertNotNull(byUuid);
        Assertions.assertEquals("delete", byUuid.getToolName());

        Assertions.assertNull(HITL.getPendingTaskByCallUuid(session, "missing"));

        HITLTask byName = HITL.getPendingTaskByToolName(session, "delete");
        Assertions.assertNotNull(byName);
        Assertions.assertEquals("c2", byName.getCallUuid());

        try {
            HITL.getPendingTaskByToolName(session, "transfer");
            Assertions.fail("同名多实例应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }

        List<HITLTask> allTransfer = HITL.getPendingTasksByToolName(session, "transfer");
        Assertions.assertEquals(2, allTransfer.size());

        HITL.submit(session, byUuid, HITLDecision.approve().comment("ok"));
        HITLDecision d = HITL.getDecision(session, byUuid);
        Assertions.assertNotNull(d);
        Assertions.assertTrue(d.isApproved());
        // 决策仅按 callUuid 存储，不再双写 toolName 键
        Assertions.assertNull(HITL.getDecision(session, "delete"));
        Assertions.assertNotNull(HITL.getDecision(session, "c2"));

        HITL.approve(session, HITL.getPendingTaskByCallUuid(session, "c1"));
        Assertions.assertTrue(HITL.getDecision(session, "c1").isApproved());
        Assertions.assertNull(HITL.getDecision(session, "transfer"));
    }

    /**
     * 兼容旧 toolName / ByCallUuid API 仍可用。
     */
    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedStringApisStillWork() {
        AgentSession session = InMemoryAgentSession.of("batch_dep");
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Collections.singletonList(
                new HITLTask("only-1", "transfer", mapOf("a", 1), "need")
        )));

        HITL.approve(session, "transfer");
        Assertions.assertTrue(HITL.getDecision(session, "only-1").isApproved());

        HITL.clear(session);
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Collections.singletonList(
                new HITLTask("only-2", "delete", mapOf("b", 2), "need")
        )));
        HITL.reject(session, "delete", "no");
        Assertions.assertTrue(HITL.getDecision(session, "only-2").isRejected());
    }

    /**
     * getPendingTasks 返回只读视图，业务 clear 不应污染 session。
     */
    @Test
    public void getPendingTasksIsUnmodifiable() {
        AgentSession session = InMemoryAgentSession.of("batch_ro");
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Collections.singletonList(
                new HITLTask("c1", "transfer", mapOf("a", 1), "need")
        )));
        List<HITLTask> view = HITL.getPendingTasks(session);
        try {
            view.clear();
            Assertions.fail("应抛出 UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        Assertions.assertEquals(1, HITL.getPendingTasks(session).size());
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
