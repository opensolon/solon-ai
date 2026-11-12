package features.ai.harness;

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.GenerateTalent;
import org.noear.solon.ai.harness.agent.TaskTalent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task / multitask / generate 的成败标记（文本不变，失败标记给宿主看）：
 * <ul>
 *   <li>task：嵌套、找不到代理等没跑成算失败；</li>
 *   <li>multitask：嵌套、取消/中断、一个都没成功算失败；部分失败算成功；空列表（WARNING）算成功；</li>
 *   <li>generate：ERROR 分支算失败，[OK] 算成功。</li>
 * </ul>
 */
public class TaskTalentErrorFlagTest {
    @TempDir
    Path ws;

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Boolean> depth() throws Exception {
        return (ThreadLocal<Boolean>) HarnessFixtures.getStatic(TaskTalent.class, "TASK_DEPTH");
    }

    private static void assertError(ToolResult r) {
        assertTrue(r.isError(), r.getContent());
    }

    private static void assertOk(ToolResult r) {
        assertFalse(r.isError(), r.getContent());
    }

    @Test
    public void nestedCallsAreErrors() throws Exception {
        TaskTalent talent = HarnessFixtures.engine(ws).getTaskTalent();
        ThreadLocal<Boolean> depth = depth();
        depth.set(Boolean.TRUE);
        try {
            ToolResult t = talent.task(HarnessFixtures.taskOp("explore", 1), ws.toString(), "s1", null);
            assertError(t);
            assertTrue(t.getContent().startsWith("ERROR: "), t.getContent());

            ToolResult m = talent.multitask(Collections.singletonList(HarnessFixtures.taskOp("explore", 1)),
                    ws.toString(), "s1", null);
            assertError(m);
            assertTrue(m.getContent().startsWith("ERROR: "), m.getContent());
        } finally {
            depth.remove();
        }
    }

    @Test
    public void emptyTasksIsNotError() throws Exception {
        TaskTalent talent = HarnessFixtures.engine(ws).getTaskTalent();

        ToolResult r = talent.multitask(Collections.emptyList(), ws.toString(), "s1", null);

        assertOk(r);
        assertTrue(r.getContent().startsWith("WARNING: "), r.getContent());
    }

    @Test
    public void unknownAgentsAreErrors() throws Exception {
        HarnessEngine engine = HarnessFixtures.engine(ws);
        AgentSession s1 = engine.getSession("s1");
        HarnessFixtures.bindTrace(s1, HarnessFixtures.trace(null, false));
        TaskTalent talent = engine.getTaskTalent();

        TaskTalent.SingleTaskOp single = new TaskTalent.SingleTaskOp();
        single.agent_name = "no-such-agent";
        single.description = "d";
        single.prompt = "p";
        assertError(talent.task(single, ws.toString(), "s1", null));

        List<TaskTalent.MultiTaskOp> bad = new ArrayList<>();
        bad.add(HarnessFixtures.taskOp("no-such-a", 1));
        bad.add(HarnessFixtures.taskOp("no-such-b", 2));
        ToolResult all = talent.multitask(bad, ws.toString(), "s1", null);
        assertError(all);
        // 默认语言旧签名逐字一致
        assertEquals(all.getContent(), talent.multitask(bad, ws.toString(), "s1"));
    }

    /**
     * 聚合判定：部分成功算成功；一个都没成功、被取消算失败。
     * 成功数只在 runTask 的成功出口计数，这里直接设置以隔离判定逻辑（真实子代理需要可用模型）。
     */
    @Test
    public void aggregateFlagFollowsSucceededCount() throws Throwable {
        TaskTalent talent = HarnessFixtures.engine(ws).getTaskTalent();
        ReActTrace trace = HarnessFixtures.trace(null, false);

        // 部分成功
        Object partial = ctx(trace);
        succeeded(partial).set(1);
        assertOk(HarnessFixtures.aggregate(talent, okAndFailed(), partial));

        // 一个都没成功
        Object none = ctx(trace);
        assertError(HarnessFixtures.aggregate(talent, okAndFailed(), none));

        // 被取消：即使已有成功项也算失败
        Object cancelled = ctx(HarnessFixtures.trace(null, true));
        succeeded(cancelled).set(1);
        List<CompletableFuture<String>> pending = new ArrayList<>();
        pending.add(new CompletableFuture<>());
        assertError(HarnessFixtures.aggregate(talent, pending, cancelled));
    }

    /**
     * 经工具注册路径：失败标记能传出（确认带 __locale 的重载被选中）
     */
    @Test
    public void registeredToolFlags() throws Throwable {
        TaskTalent talent = HarnessFixtures.engine(ws).getTaskTalent();

        FunctionTool multitask = talent.getToolMap().get("multitask");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tasks", Collections.emptyList());
        args.put("__sessionId", "s1");
        assertOk(multitask.call(args));

        ThreadLocal<Boolean> depth = depth();
        depth.set(Boolean.TRUE);
        try {
            Map<String, Object> taskArgs = new LinkedHashMap<>();
            taskArgs.put("agent_name", "explore");
            taskArgs.put("description", "d");
            taskArgs.put("prompt", "p");
            taskArgs.put("__sessionId", "s1");
            assertError(talent.getToolMap().get("task").call(taskArgs));
        } finally {
            depth.remove();
        }
    }

    @Test
    public void generateFlags() throws Throwable {
        HarnessEngine engine = HarnessFixtures.engine(ws);
        engine.getSession("s1");
        GenerateTalent gen = engine.getGenerateTalent();

        ToolResult bad = gen.generate("bad name", "d", "sp", null, null, false, ws.toString(), "s1", null);
        assertError(bad);
        assertTrue(bad.getContent().startsWith("ERROR: "), bad.getContent());
        assertError(gen.generate("a1", "d", "sp", null, null, false, ws.toString(), "no-such-session", null));

        ToolResult ok = gen.generate("a1", "d", "sp", null, null, false, ws.toString(), "s1", null);
        assertOk(ok);
        assertTrue(ok.getContent().startsWith("[OK] "), ok.getContent());

        // 默认语言旧签名（返回文本）：与新签名逐字一致
        assertEquals(gen.generate("a2", "d", "sp", null, null, false, ws.toString(), "no-such-session", null).getContent(),
                gen.generate("a2", "d", "sp", null, null, false, ws.toString(), "no-such-session"));
        assertTrue(gen.generate("a2", "d", "sp", null, null, false, ws.toString(), "s1").startsWith("[OK] "));

        // 工具注册路径
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "bad name");
        args.put("description", "d");
        args.put("systemPrompt", "sp");
        args.put("__sessionId", "s1");
        assertError(gen.getToolMap().get("generate").call(args));
    }

    /**
     * 4.1.1 的 7 参签名（无会话）仍可调用：等同 8 参传 null 会话
     */
    @Test
    @SuppressWarnings("deprecation")
    public void generateLegacySignature() throws Throwable {
        GenerateTalent gen = HarnessFixtures.engine(ws).getGenerateTalent();

        assertEquals(gen.generate("a3", "d", "sp", null, null, false, ws.toString(), null),
                gen.generate("a3", "d", "sp", null, null, false, ws.toString()));
        assertTrue(gen.generate("g3", "d", "sp", null, null, true, ws.toString()).startsWith("[OK] "));
    }

    private static Object ctx(ReActTrace trace) throws Exception {
        return HarnessFixtures.multitaskContext(trace, 2,
                new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());
    }

    private static AtomicInteger succeeded(Object ctx) throws Exception {
        return (AtomicInteger) HarnessFixtures.get(ctx, "succeeded");
    }

    private static List<CompletableFuture<String>> okAndFailed() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        List<CompletableFuture<String>> futures = new ArrayList<>();
        futures.add(CompletableFuture.completedFuture("ok"));
        futures.add(failed);
        return futures;
    }
}
