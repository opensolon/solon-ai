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
 * task / task_start / task_wait / generate 的成败标记（文本不变，失败标记给宿主看）：
 * <ul>
 *   <li>task / task_start：嵌套、找不到代理等没跑成算失败；</li>
 *   <li>task_wait：没有可等的（WARNING）算成功；找不到任务算失败；</li>
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

            ToolResult m = talent.taskStart(HarnessFixtures.taskOp("explore", 1), ws.toString(), "s1", null);
            assertError(m);
            assertTrue(m.getContent().startsWith("ERROR: "), m.getContent());
        } finally {
            depth.remove();
        }
    }

    @Test
    public void waitWithNothingIsNotError() throws Exception {
        TaskTalent talent = HarnessFixtures.engine(ws).getTaskTalent();

        ToolResult r = talent.taskWait(null, null, "s1", null);

        assertOk(r);
        assertTrue(r.getContent().startsWith("WARNING: "), r.getContent());

        assertError(talent.taskWait("task-0123456789abcdef0123456789abcdef", null, "s1", null));
        assertError(talent.taskStop("task-0123456789abcdef0123456789abcdef", "s1", null));
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

        assertError(talent.taskStart(HarnessFixtures.taskOp("no-such-a", 1), ws.toString(), "s1", null));
    }

    /**
     * 经工具注册路径：失败标记能传出（确认带 __locale 的重载被选中）
     */
    @Test
    public void registeredToolFlags() throws Throwable {
        TaskTalent talent = HarnessFixtures.engine(ws).getTaskTalent();

        FunctionTool taskWait = talent.getToolMap().get(TaskTalent.TOOL_TASK_WAIT);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("__sessionId", "s1");
        assertOk(taskWait.call(args));

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

        // 全局持久化（saveToFile=true）不需要会话
        assertOk(gen.generate("g1", "d", "sp", null, null, true, ws.toString(), null, null));

        // 工具注册路径
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "bad name");
        args.put("description", "d");
        args.put("systemPrompt", "sp");
        args.put("__sessionId", "s1");
        assertError(gen.getToolMap().get("generate").call(args));
    }
}
