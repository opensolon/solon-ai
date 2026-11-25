package features.ai.harness;

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子任务由模型带 task_id 接着做：失败判定、子会话保留、续跑状态修复、归属校验、跨重启（落盘提供者）。
 *
 * <p>假模型指向不可达地址：子代理调用模型必然失败（正常结束 + 模型错误分类），正好是「可续跑失败」的形态。</p>
 */
public class TaskTalentResumeTest {
    private static final Pattern TASK_ID = Pattern.compile("<task_id>(task-[0-9a-f]{32})</task_id>");

    @TempDir
    Path ws;

    /** 关掉子代理内部重试，避免用例被重试间隔拖慢 */
    private static final HarnessExtension NO_RETRY = new HarnessExtension() {
        @Override
        public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
            o.retryConfig(0, 0);
        }
    };

    private HarnessEngine engine(AgentSessionProvider provider) throws Exception {
        HarnessEngine.Builder b = HarnessFixtures.builder(ws).extensionAdd(NO_RETRY);
        if (provider != null) {
            b.sessionProvider(provider);
        }
        HarnessEngine engine = b.build();
        for (String sid : new String[]{"s1", "s2"}) {
            HarnessFixtures.bindTrace(engine.getSession(sid), HarnessFixtures.trace(null, false));
        }
        return engine;
    }

    private static TaskTalent.SingleTaskOp op(String taskId, String prompt) {
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "d";
        op.prompt = prompt;
        op.task_id = taskId;
        return op;
    }

    private static String taskIdOf(ToolResult r) {
        Matcher m = TASK_ID.matcher(r.getContent());
        assertTrue(m.find(), r.getContent());
        return m.group(1);
    }

    /** 落盘提供者（同 soloncode SessionManager 的分工）：主会话按实例缓存，task- 子会话不缓存、每次从磁盘打开 */
    private static AgentSessionProvider fileProvider(Path root) {
        Map<String, AgentSession> cache = new ConcurrentHashMap<>();
        return (id, create) -> {
            Path dir = root.resolve(id);
            if (TaskTalent.isTaskId(id)) {
                return create || Files.isDirectory(dir) ? new FileAgentSession(id, dir.toString()) : null;
            }
            return create || Files.isDirectory(dir) ? cache.computeIfAbsent(id, k -> new FileAgentSession(k, dir.toString())) : null;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(AgentSession sub) {
        return (Map<String, Object>) sub.getContext().get("__subtask");
    }

    @Test
    public void llmFailureIsReportedAsResumableFailure() throws Exception {
        HarnessEngine engine = engine(null);

        ToolResult r = engine.getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null);

        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("<result_status>failure</result_status>"), r.getContent());
        assertTrue(r.getContent().contains("<failure_kind>"), r.getContent());

        String id = taskIdOf(r);
        AgentSession sub = engine.getSessionProvider().getSession(id, false);
        assertNotNull(sub, "失败的子会话必须保留");
        assertEquals(id, sub.getSessionId(), "task_id 即子会话 ID");
        assertTrue(TaskTalent.isTaskId(id), id);
        assertEquals("failure", meta(sub).get("status"));
        assertEquals("s1", meta(sub).get("parentSessionId"));
        assertEquals("explore", meta(sub).get("agentName"));

        // 结果上挂子任务索引（进 ToolMessage metadata，供宿主回放子过程）
        List<?> subtasks = (List<?>) r.metas().get(TaskTalent.META_SUBTASKS);
        assertNotNull(subtasks, "必须带子任务索引");
        assertEquals(id, ((Map<?, ?>) subtasks.get(0)).get("taskId"));
        assertEquals("explore", ((Map<?, ?>) subtasks.get(0)).get("agentName"));
    }

    @Test
    public void resumeContinuesTheSameSubSession() throws Exception {
        HarnessEngine engine = engine(null);
        TaskTalent talent = engine.getTaskTalent();

        String id = taskIdOf(talent.task(op(null, "p1"), ws.toString(), "s1", null));

        ToolResult again = talent.task(op(id, "继续"), ws.toString(), "s1", null);
        assertEquals(id, taskIdOf(again), "续跑沿用原 task_id");

        AgentSession sub = engine.getSessionProvider().getSession(id, false);
        assertEquals("failure", meta(sub).get("status"));

        // 上次的错误说明保留，补充指令追加其后，最后是本次的回复
        List<ChatMessage> messages = new ArrayList<>(sub.getMessages());
        assertEquals(4, messages.size(), messages.toString());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertEquals("p1", messages.get(0).getContent());
        assertTrue(messages.get(1) instanceof AssistantMessage);
        assertTrue(messages.get(2) instanceof UserMessage);
        assertEquals("继续", messages.get(2).getContent());
        assertTrue(messages.get(3) instanceof AssistantMessage);
    }

    @Test
    public void resumeIsScopedToParentSession() throws Exception {
        HarnessEngine engine = engine(null);
        TaskTalent talent = engine.getTaskTalent();

        String id = taskIdOf(talent.task(op(null, "p1"), ws.toString(), "s1", null));

        // 格式非法 / 不存在 / 别的父会话：一律按「不存在」拒绝，且不给 task_id
        for (String bad : new String[]{"../x", "0123456789abcdef0123456789abcdef", "task-0123456789abcdef0123456789abcdef"}) {
            ToolResult r = talent.task(op(bad, "继续"), ws.toString(), "s1", null);
            assertTrue(r.isError() && r.getContent().contains(bad) && !r.getContent().contains("<task_id>"), r.getContent());
        }
        ToolResult other = talent.task(op(id, "继续"), ws.toString(), "s2", null);
        assertTrue(other.isError() && !other.getContent().contains("<task_id>"), other.getContent());

        // 同一次 multitask 里重复续跑同一任务：只认第一条
        List<TaskTalent.MultiTaskOp> ops = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            TaskTalent.MultiTaskOp m = HarnessFixtures.taskOp("explore", i);
            m.task_id = id;
            ops.add(m);
        }
        String multi = talent.multitask(ops, ws.toString(), "s1", null).getContent();
        assertTrue(multi.contains("<index>2</index><description>d2</description>"), multi);

        // 不限次数、不看上次成败：已成功的任务也能接着做（追问）
        meta(engine.getSessionProvider().getSession(id, false)).put("status", "success");
        for (int i = 0; i < 3; i++) {
            assertEquals(id, taskIdOf(talent.task(op(id, "继续"), ws.toString(), "s1", null)));
        }
    }

    @Test
    public void subSessionLivesInMainProviderAndSurvivesEngineRestart() throws Exception {
        Path root = ws.resolve("sessions");

        String id = taskIdOf(engine(fileProvider(root)).getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null));
        assertTrue(Files.isDirectory(root.resolve(id)), "子会话与主会话存在同一个提供者里（按 task- 前缀区分）");

        // 新引擎 + 新的提供者实例（模拟进程重启）：只靠磁盘取回子会话
        AgentSessionProvider afterRestart = fileProvider(root);
        HarnessEngine restarted = engine(afterRestart);
        ToolResult again = restarted.getTaskTalent().task(op(id, "继续"), ws.toString(), "s1", null);
        assertEquals(id, taskIdOf(again), again.getContent());

        AgentSession sub = afterRestart.getSession(id, false);
        assertEquals("继续", new ArrayList<>(sub.getMessages()).get(2).getContent());
    }

    /** 子会话判定只认完整格式：前缀相同但不合格式的（如 task-board）不算，宿主据此清理不会误删 */
    @Test
    public void isTaskIdRequiresFullFormat() {
        assertTrue(TaskTalent.isTaskId("task-0123456789abcdef0123456789abcdef"));
        assertFalse(TaskTalent.isTaskId(null));
        assertFalse(TaskTalent.isTaskId("task-board"));
        assertFalse(TaskTalent.isTaskId("task-0123456789ABCDEF0123456789abcdef"));
        assertFalse(TaskTalent.isTaskId("task-0123456789abcdef0123456789abcde"));
        assertFalse(TaskTalent.isTaskId("0123456789abcdef0123456789abcdef"));
        assertFalse(TaskTalent.isTaskId("task-0123456789abcdef0123456789abcdef/../x"));
    }
}
