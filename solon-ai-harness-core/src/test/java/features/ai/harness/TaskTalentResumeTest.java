package features.ai.harness;

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessEnginePool;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.session.SessionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    /** 试图把子任务数写成 0 的扩展：引擎事实必须在扩展之后写入，不能被它覆盖 */
    private static final HarnessExtension OVERRIDE_SUBTASKS = new HarnessExtension() {
        @Override
        public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {
            builder.runningSubtaskCount(0);
        }
    };

    private HarnessEngine engine(AgentSessionProvider provider) throws Exception {
        HarnessEngine.Builder b = HarnessFixtures.builder(ws).extensionAdd(NO_RETRY).extensionAdd(OVERRIDE_SUBTASKS);
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

    /**
     * 落盘提供者（同 soloncode SessionManager 的分工）：主会话按实例缓存；task- 子会话存在父会话之下
     * （{@code <父>/subtasks/<子>}，删父会话目录即连带删除），不缓存、每次从磁盘打开。
     */
    private static AgentSessionProvider fileProvider(Path root) {
        Map<String, AgentSession> cache = new ConcurrentHashMap<>();
        return (id, parentId, create) -> {
            if (TaskTalent.isTaskId(id) && parentId != null) {
                Path dir = subDir(root, parentId, id);
                return create || Files.isDirectory(dir) ? new FileAgentSession(id, dir.toString()) : null;
            }
            Path dir = root.resolve(id);
            return create || Files.isDirectory(dir) ? cache.computeIfAbsent(id, k -> new FileAgentSession(k, dir.toString())) : null;
        };
    }

    private static Path subDir(Path root, String parentId, String taskId) {
        return root.resolve(parentId).resolve("subtasks").resolve(taskId);
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
        AgentSession sub = engine.getSessionProvider().getSession(id, null, false);
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

        AgentSession sub = engine.getSessionProvider().getSession(id, null, false);
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

        // 不限次数、不看上次成败：已成功的任务也能接着做（追问）
        meta(engine.getSessionProvider().getSession(id, null, false)).put("status", "success");
        for (int i = 0; i < 3; i++) {
            assertEquals(id, taskIdOf(talent.task(op(id, "继续"), ws.toString(), "s1", null)));
        }
    }

    @Test
    public void subSessionLivesInMainProviderAndSurvivesEngineRestart() throws Exception {
        Path root = ws.resolve("sessions");

        String id = taskIdOf(engine(fileProvider(root)).getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null));
        assertTrue(Files.isDirectory(subDir(root, "s1", id)), "子会话与主会话存在同一个提供者里，并挂在父会话之下");
        assertFalse(Files.exists(root.resolve(id)), "带父会话标识取：不再平铺在根下");

        // 新引擎 + 新的提供者实例（模拟进程重启）：只靠磁盘取回子会话
        AgentSessionProvider afterRestart = fileProvider(root);
        HarnessEngine restarted = engine(afterRestart);
        ToolResult again = restarted.getTaskTalent().task(op(id, "继续"), ws.toString(), "s1", null);
        assertEquals(id, taskIdOf(again), again.getContent());

        AgentSession sub = afterRestart.getSession(id, "s1", false);
        assertEquals("继续", new ArrayList<>(sub.getMessages()).get(2).getContent());
    }

    /** 嵌套存储：别的父会话拿同一个 task_id 续跑，在它自己名下直接查无此任务（不新建、不给 task_id） */
    @Test
    public void nestedSubSessionIsInvisibleToOtherParent() throws Exception {
        Path root = ws.resolve("sessions");
        HarnessEngine engine = engine(fileProvider(root));

        String id = taskIdOf(engine.getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null));

        ToolResult other = engine.getTaskTalent().task(op(id, "继续"), ws.toString(), "s2", null);
        assertTrue(other.isError() && !other.getContent().contains("<task_id>"), other.getContent());
        assertFalse(Files.exists(subDir(root, "s2", id)), "只取不建：别的父会话下不能留下空壳");
        assertNull(engine.getSession(id, "s2", false));
        assertNotNull(engine.getSession(id, "s1", false));
    }

    /**
     * 子任务还在跑时（如父流已取消、子任务卡在长工具调用里）：父会话不算占用（可接新输入），但不可删除；
     * 登记先于建子会话，结束后清掉。用「建子会话时阻塞」的提供者把子任务定格在运行中。
     */
    @Test
    public void runningSubtaskBlocksRemovalButNotOccupancy() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> seenParent = new AtomicReference<>();
        HarnessEngine engine = engine(blockingOnSubtaskCreate(entered, release, seenParent));
        AgentSession parent = engine.getSession("s1");
        assertTrue(engine.status(parent).isRemovable());

        Thread worker = new Thread(() -> engine.getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null));
        worker.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "子任务应已开始建子会话");
            assertEquals("s1", seenParent.get(), "建子会话时带上父会话标识");

            SessionStatus st = engine.status(parent);
            assertEquals(1, st.getRunningSubtaskCount(), "扩展写成 0 也覆盖不了引擎事实：" + st);
            assertFalse(st.isOccupied(), "子任务不占用会话：不能卡住接新输入与积压续发");
            assertFalse(st.isRemovable(), "子任务未结束不可删除");
            assertEquals(0, engine.status(engine.getSession("s2")).getRunningSubtaskCount(), "只算本会话的");
            assertEquals(0, SessionStatus.of(parent).getRunningSubtaskCount(), "静态 of 不含引擎事实");
        } finally {
            release.countDown();
            worker.join(30000);
        }

        assertFalse(worker.isAlive());
        assertEquals(0, engine.getTaskTalent().getRunningSubtaskCount("s1"));
        assertTrue(engine.status(parent).isRemovable(), "结束后可删除");
    }

    /**
     * 引擎池：子任务还在跑（父流已结束）时引擎不静默、不被回收。
     * 否则同租户会新建一个引擎，它看不到这些子任务，{@code isRemovable()} 放行删除，子任务结束时又把子会话写回来。
     */
    @Test
    public void poolKeepsEngineWhileSubtaskStillRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HarnessEngine engine = engine(blockingOnSubtaskCreate(entered, release, new AtomicReference<>()));
        // 空闲即可回收、不起后台回收线程：回收与否只取决于 isQuiescent
        HarnessEnginePool pool = HarnessEnginePool.builder(k -> engine)
                .idleTtl(Duration.ZERO).reapInterval(Duration.ZERO).build();
        try {
            assertSame(engine, pool.get("t"));
            assertTrue(engine.isQuiescent());

            Thread worker = new Thread(() -> engine.getTaskTalent().task(op(null, "p1"), ws.toString(), "s1", null));
            worker.start();
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "子任务应已开始建子会话");
                assertFalse(engine.isQuiescent(), "还有子任务在跑，引擎不静默");
                assertEquals(0, pool.evictIdle(), "不得回收");
                assertSame(engine, pool.getIfPresent("t"));
            } finally {
                release.countDown();
                worker.join(30000);
            }

            assertFalse(worker.isAlive());
            assertTrue(engine.isQuiescent(), "子任务结束后恢复静默");
            assertEquals(1, pool.evictIdle(), "结束后可回收");
        } finally {
            pool.close();
        }
    }

    /** 建 task- 子会话时阻塞的内存提供者：把子任务定格在「已登记、未结束」 */
    private static AgentSessionProvider blockingOnSubtaskCreate(CountDownLatch entered, CountDownLatch release,
                                                                AtomicReference<String> seenParent) {
        Map<String, AgentSession> store = new ConcurrentHashMap<>();
        return (id, parentId, create) -> {
            if (TaskTalent.isTaskId(id) && create) {
                seenParent.set(parentId);
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return create ? store.computeIfAbsent(id, InMemoryAgentSession::of) : store.get(id);
        };
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
