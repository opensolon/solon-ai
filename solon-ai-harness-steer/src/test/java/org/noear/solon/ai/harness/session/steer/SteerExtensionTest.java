package org.noear.solon.ai.harness.session.steer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.session.queue.QueueExtension;
import org.noear.solon.ai.harness.session.queue.QueueHelper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SteerExtension 门面验证：一键装配、投递 / 撤销 / 查询语义、开关、容量可配、消息协议、兼容层。
 */
@SuppressWarnings("deprecation")
class SteerExtensionTest {
    @TempDir
    Path workspace;

    private HarnessEngine newEngine() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .build();
    }

    /** 模拟任务已开轮（等价于拦截器 onAgentStart） */
    private static AgentSession runningSession(String runId) {
        AgentSession session = InMemoryAgentSession.of();
        SteerState.of(session).open(runId);
        return session;
    }

    private static String src(String file) throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/org/noear/solon/ai/harness/session/steer/" + file)), StandardCharsets.UTF_8);
    }

    // ==================== 装配 ====================

    @Test
    void extensionRegistersSteerCommandOnAdd() {
        HarnessEngine engine = newEngine();
        engine.addExtension(new SteerExtension());

        assertNotNull(engine.getCommandRegistry().find("steer"), "/steer 命令应自动注册");
    }

    @Test
    void commandRegistrationIsIdempotentAndCanBeDisabled() {
        HarnessEngine engine = newEngine();
        engine.getCommandRegistry().register(new SteerCommand());
        engine.addExtension(new SteerExtension());
        assertNotNull(engine.getCommandRegistry().find("steer"));

        HarnessEngine engine2 = newEngine();
        SteerExtension disabled = new SteerExtension();
        disabled.setEnabled(false);
        engine2.addExtension(disabled);
        assertNull(engine2.getCommandRegistry().find("steer"));
        assertNotNull(engine2.getCommandRegistry().get("steer"), "已注册才能区分停用与未注册");
    }

    @Test
    void requestAssembleMountsInterceptorForTopLevelRequestsOnly() throws Exception {
        // 源码契约：挂载逻辑必须在 onRequestAssemble 上，且以 isDelegated 排除 task/multitask 委派；
        // 不能退回「主代理默认拦截器」——那样面板选中的子代理（同为顶层请求）就命不中。
        String src = src("SteerExtension.java");
        assertTrue(src.contains("optionsAmend.interceptorAdd(interceptor)"),
                "onRequestAssemble 必须把拦截器挂到请求级 options");
        assertTrue(src.contains("public void onRequestAssemble"),
                "挂载点必须是 onRequestAssemble");
        assertTrue(src.contains("isDelegated == false"),
                "必须排除 task/multitask 委派的子代理任务（临时会话无插话语义）");
        assertFalse(src.contains("agentBuilder.defaultInterceptorAdd(interceptor)"),
                "不得退回主代理默认拦截器（会丢失面板选中子代理的场景）");
    }

    /** 轮次由拦截器在 onAgentStart 自动开启：宿主无开流前义务（源码契约，防回退成 beginTurn 义务）。 */
    @Test
    void turnHandoverIsOwnedByInterceptorNotHost() throws Exception {
        String interceptor = src("SteerInterceptor.java");
        assertTrue(interceptor.contains("public void onAgentStart(ReActTrace trace)"),
                "开轮必须在拦截器 onAgentStart，而不是宿主开流前调用");
        assertTrue(interceptor.contains("SteerDroppedEvent.Reason.RUN_SUPERSEDED"),
                "中断后的残留必须经事件这一条通道输出");
    }

    // ==================== 投递 ====================

    @Test
    void disabledExtensionRejectsSteer() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        ext.setEnabled(false);
        SteerResult r = ext.steer(session, "补充", null);
        assertEquals(SteerStatus.DISABLED, r.getStatus());
        assertEquals(0, r.getQueued());
        assertEquals(SteerStatus.DISABLED, ext.steerOnRun(session, "run-1", null, "补充").getStatus());
    }

    @Test
    void steerRequiresActiveRun() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        assertEquals(SteerStatus.NOT_RUNNING, ext.steer(session, "补充").getStatus());
        assertEquals(SteerStatus.NOT_RUNNING, ext.steer(null, "补充").getStatus());

        SteerState.of(session).open("run-1");
        SteerResult ok = ext.steer(session, "补充内容");
        assertTrue(ok.isSteered());
        assertEquals(1, ok.getQueued());
        assertNotNull(ok.getSteerId(), "steerId 应由框架生成");
    }

    @Test
    void steerKeepsEmptyTextGuardAndNullOrigin() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        assertEquals(SteerStatus.EMPTY_TEXT, ext.steer(session, "   ").getStatus());
        assertTrue(ext.steer(session, "补充", null).isSteered(), "null 来源应回落 InputOrigin.NONE 而非报错");
    }

    /** 引擎缺省忙判定：attach 过引擎且会话无活跃流 → 投递被回滚为 NOT_RUNNING。 */
    @Test
    void steerUsesEngineBusyDefault() {
        HarnessEngine engine = newEngine();
        SteerExtension ext = new SteerExtension();
        engine.addExtension(ext);

        AgentSession session = runningSession("run-1");

        SteerResult r = ext.steer(session, "补充", null);
        assertEquals(SteerStatus.NOT_RUNNING, r.getStatus(), "无活跃流时应被回滚");
        assertTrue(ext.pending(session).isEmpty(), "回滚后邮箱不得残留");
    }

    /** 未 attach 引擎（纯单测门面）时忙判定放行，只依据轮次判定。 */
    @Test
    void detachedExtensionDoesNotBlockSteer() {
        SteerExtension ext = new SteerExtension();
        assertTrue(ext.steer(runningSession("run-1"), "补充").isSteered(), "无引擎即无忙概念，应放行");
    }

    /** steerOnRun 的 runId 必填：空即 TURN_CHANGED，不生成 steerId、不碰邮箱。 */
    @Test
    void steerOnRunRequiresRunId() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        SteerResult blank = ext.steerOnRun(session, "  ", null, "补充");
        assertEquals(SteerStatus.TURN_CHANGED, blank.getStatus());
        assertNull(blank.getSteerId(), "被拒绝的投递不得生成 steerId");
        assertTrue(ext.pending(session).isEmpty());

        assertEquals(SteerStatus.TURN_CHANGED, ext.steerOnRun(session, null, null, "补充").getStatus());
        assertTrue(ext.steer(session, "补充").isSteered(), "非严格形态允许省略 runId");
    }

    @Test
    void steerOnRunRejectsForeignRunId() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        assertEquals(SteerStatus.TURN_CHANGED, ext.steerOnRun(session, "run-2", null, "补充").getStatus());
        SteerResult ok = ext.steerOnRun(session, "run-1", "web-1", "补充");
        assertTrue(ok.isSteered());
        assertEquals("web-1", ok.getSteerId(), "前端生成的 steerId 原样使用");
    }

    // ==================== 撤销 ====================

    @Test
    void cancelOnRunValidatesIdAndTurn() {
        SteerExtension ext = SteerExtension.builder().maxIdLength(4).build();
        AgentSession session = runningSession("run-1");
        SteerResult a = ext.steerOnRun(session, "run-1", "a", "第一条");
        ext.steerOnRun(session, "run-1", "b", "第二条");

        assertEquals(SteerCancelStatus.INVALID_STEER_ID, ext.cancelOnRun(session, "run-1", " ").getStatus());
        assertEquals(SteerCancelStatus.INVALID_STEER_ID, ext.cancelOnRun(session, "run-1", "12345").getStatus(),
                "超长按实例上限判定");
        assertEquals(SteerCancelStatus.TURN_CHANGED, ext.cancelOnRun(session, "run-2", "a").getStatus());
        assertEquals(SteerCancelStatus.TURN_CHANGED, ext.cancelOnRun(session, null, "a").getStatus());

        SteerCancelResult ok = ext.cancelOnRun(session, "run-1", " " + a.getSteerId() + " ");
        assertTrue(ok.isCanceled());
        assertEquals("a", ok.getSteerId());
        assertEquals(1, ok.getCanceled());
        assertEquals(1, ok.getRemaining(), "带出剩余条数，宿主无需再查 pending");

        assertEquals(SteerCancelStatus.NOT_PENDING, ext.cancelOnRun(session, "run-1", "a").getStatus(),
                "已不在待生效列表不得宣称撤销成功");
        assertEquals(SteerCancelStatus.NOT_PENDING,
                ext.cancelOnRun(InMemoryAgentSession.of(), "run-1", "a").getStatus(), "无状态的会话");
        assertEquals(SteerCancelStatus.NOT_PENDING, ext.cancelOnRun(null, "run-1", "a").getStatus());
    }

    @Test
    void cancelPendingCountsOnlyActuallyRemovedAndClearsAll() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        SteerResult a = ext.steer(session, "第一条");
        SteerResult b = ext.steer(session, "第二条");
        ext.steer(session, "第三条");
        // 模拟消费线程已取走 a
        SteerState.of(session).drainFor("run-1");
        ext.steer(session, "第四条");

        SteerCancelResult r = ext.cancelPending(session, Arrays.asList(a.getSteerId(), b.getSteerId(), " "));
        assertEquals(SteerCancelStatus.CANCELED, r.getStatus());
        assertEquals(0, r.getCanceled(), "已被消费的不应计入");
        assertEquals(1, r.getRemaining());

        SteerCancelResult all = ext.cancelPending(session, null);
        assertEquals(1, all.getCanceled());
        assertEquals(0, all.getRemaining());
        assertTrue(ext.pending(session).isEmpty());

        assertEquals(0, ext.cancelPending(InMemoryAgentSession.of(), null).getCanceled(), "无状态零行为");
    }

    // ==================== 查询 ====================

    @Test
    void pendingListsUnappliedSteersAsSnapshotCopy() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        assertTrue(ext.pending(session).isEmpty(), "无状态时返回空集合（不是 null）");
        assertTrue(ext.pending(null).isEmpty());

        SteerState.of(session).open("run-1");
        SteerResult a = ext.steer(session, "第一条");
        ext.steer(session, "第二条");

        List<SteerMessage> snapshot = ext.pending(session);
        assertEquals(2, snapshot.size());
        assertEquals(a.getSteerId(), snapshot.get(0).getId());
        assertEquals("第二条", snapshot.get(1).getText());

        ext.cancelPending(session, null);
        assertEquals(2, snapshot.size(), "返回的是副本，不是活动视图");
    }

    @Test
    void activeRunIdFollowsTurn() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        assertNull(ext.activeRunId(session));
        assertNull(ext.activeRunId(null));

        SteerState.of(session).open("run-1");
        assertEquals("run-1", ext.activeRunId(session));

        SteerState.of(session).close("run-1");
        assertNull(ext.activeRunId(session));
    }

    // ==================== 容量可配 ====================

    @Test
    void builderLimitsAreEnforced() {
        SteerExtension ext = SteerExtension.builder().maxBoxSize(2).maxTextLength(8).build();
        assertEquals(2, ext.getLimits().getMaxBoxSize());

        AgentSession session = runningSession("run-1");

        assertTrue(ext.steer(session, "a").isSteered());
        assertTrue(ext.steer(session, "b").isSteered());
        assertTrue(ext.steer(session, "c").isBoxFull(), "超过 builder 上限应 BOX_FULL");

        assertEquals(SteerStatus.TEXT_TOO_LONG, ext.steer(session, "123456789").getStatus(),
                "超过 builder 文本上限应 TEXT_TOO_LONG");
    }

    @Test
    void defaultConstructorUsesDefaultLimits() {
        SteerExtension ext = new SteerExtension();
        assertEquals(SteerHelper.MAX_BOX_SIZE, ext.getLimits().getMaxBoxSize());
        assertEquals(SteerHelper.MAX_TEXT_LENGTH, ext.getLimits().getMaxTextLength());
        assertEquals(SteerHelper.MAX_ID_LENGTH, ext.getLimits().getMaxIdLength());
    }

    // ==================== 消息协议 ====================

    @Test
    void steerMessagesTextOfRecognizesBothMarks() {
        ChatMessage injected = SteerMessages.toChatMessage(new SteerMessage("s1", "别改测试"));
        assertEquals("别改测试", SteerMessages.textOf(injected));

        // metadata 在快照往返中丢失：前缀兜底
        assertEquals("换个思路", SteerMessages.textOf(ChatMessage.ofUser(SteerMessages.PREFIX + "换个思路")));

        // 只有 metadata：原样返回
        ChatMessage marked = ChatMessage.ofUser("无前缀");
        marked.addMetadata("source", SteerMessages.SOURCE);
        assertEquals("无前缀", SteerMessages.textOf(marked));

        assertNull(SteerMessages.textOf(ChatMessage.ofUser("普通提问")));
        assertNull(SteerMessages.textOf(ChatMessage.ofAssistant(SteerMessages.PREFIX + "x")), "只认 UserMessage");
        assertNull(SteerMessages.textOf(ChatMessage.ofUser("")));
        assertNull(SteerMessages.textOf(null));
    }

    // ==================== 兼容层（已弃用，下个 minor 移除） ====================

    @Test
    void deprecatedFacadeStillWorks() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        SteerResult a = ext.steer(session, null, null, "a", null, null);
        SteerResult b = ext.steer(session, "run-1", null, "b", null);
        assertTrue(a.isSteered());
        assertTrue(b.isSteered());
        assertTrue(ext.cancel(session, a.getSteerId()));
        assertFalse(ext.cancel(session, a.getSteerId()));
        assertEquals(1, ext.cancelAll(session, Arrays.asList(b.getSteerId())));
        assertEquals(0, ext.cancelAll(session, null), "旧语义：未指定 id 不做任何事");

        ext.steer(session, "c");
        assertEquals(1, ext.clearPending(session).size());
        assertTrue(ext.clearPending(session).isEmpty());

        assertNotNull(ext.getInterceptor());
        assertEquals(SteerHelper.MAX_TEXT_LENGTH, SteerExtension.maxTextLength());
        assertTrue(SteerExtension.isSteerMessage(ChatMessage.ofUser(SteerMessages.PREFIX + "x")));
        assertEquals("x", SteerExtension.stripSteerPrefix(SteerMessages.PREFIX + "x"));
    }

    /** 已弃用的 beginTurn 仍是可用的强制交接（兼容未迁移的宿主），失败项暂存而非回填邮箱。 */
    @Test
    void deprecatedBeginTurnStillHandsOver() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");
        ext.steer(session, "残留插话");

        // 转排队目标按能力接口从引擎现取：queue 挂在 steer 之后也生效（与装配顺序无关）
        HarnessEngine engine = newEngine();
        engine.addExtension(ext);
        engine.addExtension(new QueueExtension());

        SteerExtension.SteerHandover handed = ext.beginTurn(session);
        assertEquals(1, handed.getQueued().size(), "残留插话应转入排队");
        assertEquals(1, QueueHelper.pendingSize(session));
        assertNull(ext.activeRunId(session), "轮次标识应被清空");
        assertTrue(ext.beginTurn(session).isEmpty(), "幂等");
    }

    /** 引擎上没有积压能力（未装 queue）：交接全部按失败暂存，不得绕过接口写队列。 */
    @Test
    void handoverWithoutBacklogParksAsFailed() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");
        ext.steer(session, "残留插话");
        newEngine().addExtension(ext);

        SteerExtension.SteerHandover handed = ext.beginTurn(session);
        assertEquals(0, handed.getQueued().size());
        assertEquals(1, handed.getFailed().size());
        assertEquals(0, QueueHelper.pendingSize(session));
        assertEquals(1, ext.pending(session).size(), "失败项暂存为 orphans，仍可撤销");
    }
}
