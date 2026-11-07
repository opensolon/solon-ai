package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.harness.HarnessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;

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
class SteerExtensionTest {
    @TempDir
    Path workspace;

    private HarnessEngine newEngine() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
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
                "src/main/java/org/noear/solon/ai/harness/extension/steer/" + file)), StandardCharsets.UTF_8);
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
        HarnessResult<SteerReceipt> r = ext.steer(session, "补充", null);
        assertEquals("DISABLED", r.getReasonOrCode());
        assertNull(r.getValue(), "失败结局没有回执");
        assertEquals("DISABLED", ext.steerOnRun(session, "run-1", null, "补充").getReasonOrCode());
    }

    @Test
    void steerRequiresActiveRun() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        assertEquals("NOT_RUNNING", ext.steer(session, "补充").getReasonOrCode());
        assertEquals("NOT_RUNNING", ext.steer(null, "补充").getReasonOrCode());

        SteerState.of(session).open("run-1");
        HarnessResult<SteerReceipt> ok = ext.steer(session, "补充内容");
        assertTrue(ok.isOk());
        assertEquals(1, ok.getValue().getPending());
        assertNotNull(ok.getValue().getSteerId(), "steerId 应由框架生成");
    }

    @Test
    void steerKeepsEmptyTextGuardAndNullOrigin() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        assertEquals("EMPTY_TEXT", ext.steer(session, "   ").getReasonOrCode());
        assertTrue(ext.steer(session, "补充", null).isOk(), "null 来源应回落 InputOrigin.NONE 而非报错");
    }

    /** 引擎缺省忙判定：attach 过引擎且会话无活跃流 → 投递被回滚为 NOT_RUNNING。 */
    @Test
    void steerUsesEngineBusyDefault() {
        HarnessEngine engine = newEngine();
        SteerExtension ext = new SteerExtension();
        engine.addExtension(ext);

        AgentSession session = runningSession("run-1");

        HarnessResult<SteerReceipt> r = ext.steer(session, "补充", null);
        assertEquals("NOT_RUNNING", r.getReasonOrCode(), "无活跃流时应被回滚");
        assertTrue(ext.pending(session).isEmpty(), "回滚后邮箱不得残留");
    }

    /** 未 attach 引擎（纯单测门面）时忙判定放行，只依据轮次判定。 */
    @Test
    void detachedExtensionDoesNotBlockSteer() {
        SteerExtension ext = new SteerExtension();
        assertTrue(ext.steer(runningSession("run-1"), "补充").isOk(), "无引擎即无忙概念，应放行");
    }

    /** steerOnRun 的 runId 必填：空即 RUN_CHANGED，不生成 steerId、不碰邮箱。 */
    @Test
    void steerOnRunRequiresRunId() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        HarnessResult<SteerReceipt> blank = ext.steerOnRun(session, "  ", null, "补充");
        assertEquals("RUN_CHANGED", blank.getReasonOrCode());
        assertNull(blank.getValue(), "被拒绝的投递不得生成 steerId");
        assertTrue(ext.pending(session).isEmpty());

        assertEquals("RUN_CHANGED", ext.steerOnRun(session, null, null, "补充").getReasonOrCode());
        assertTrue(ext.steer(session, "补充").isOk(), "非严格形态允许省略 runId");
    }

    @Test
    void steerOnRunRejectsForeignRunId() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        assertEquals("RUN_CHANGED", ext.steerOnRun(session, "run-2", null, "补充").getReasonOrCode());
        HarnessResult<SteerReceipt> ok = ext.steerOnRun(session, "run-1", "web-1", "补充");
        assertTrue(ok.isOk());
        assertEquals("web-1", ok.getValue().getSteerId(), "前端生成的 steerId 原样使用");
    }

    // ==================== 撤销 ====================

    @Test
    void cancelOnRunValidatesIdAndTurn() {
        SteerExtension ext = SteerExtension.builder().maxIdLength(4).build();
        AgentSession session = runningSession("run-1");
        HarnessResult<SteerReceipt> a = ext.steerOnRun(session, "run-1", "a", "第一条");
        ext.steerOnRun(session, "run-1", "b", "第二条");

        assertEquals("INVALID_STEER_ID", ext.cancelOnRun(session, "run-1", " ").getReasonOrCode());
        assertEquals("INVALID_STEER_ID", ext.cancelOnRun(session, "run-1", "12345").getReasonOrCode(),
                "超长按实例上限判定");
        assertEquals("RUN_CHANGED", ext.cancelOnRun(session, "run-2", "a").getReasonOrCode());
        assertEquals("RUN_CHANGED", ext.cancelOnRun(session, null, "a").getReasonOrCode());

        HarnessResult<SteerReceipt> ok = ext.cancelOnRun(session, "run-1", " " + a.getValue().getSteerId() + " ");
        assertTrue(ok.isOk());
        assertEquals("a", ok.getValue().getSteerId());
        assertEquals(1, ok.getValue().getCanceled());
        assertEquals(1, ok.getValue().getPending(), "带出剩余条数，宿主无需再查 pending");

        assertEquals("NOT_PENDING", ext.cancelOnRun(session, "run-1", "a").getReasonOrCode(),
                "已不在待生效列表不得宣称撤销成功");
        assertEquals("NOT_PENDING", ext.cancelOnRun(InMemoryAgentSession.of(), "run-1", "a").getReasonOrCode(), "无状态的会话");
        assertEquals("NOT_PENDING", ext.cancelOnRun(null, "run-1", "a").getReasonOrCode());
    }

    @Test
    void cancelPendingCountsOnlyActuallyRemovedAndClearsAll() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = runningSession("run-1");

        HarnessResult<SteerReceipt> a = ext.steer(session, "第一条");
        HarnessResult<SteerReceipt> b = ext.steer(session, "第二条");
        ext.steer(session, "第三条");
        // 模拟消费线程已取走 a
        SteerState.of(session).drainFor("run-1");
        ext.steer(session, "第四条");

        HarnessResult<SteerReceipt> r = ext.cancelPending(session, Arrays.asList(a.getValue().getSteerId(), b.getValue().getSteerId(), " "));
        assertEquals("OK", r.getReasonOrCode());
        assertEquals(0, r.getValue().getCanceled(), "已被消费的不应计入");
        assertEquals(1, r.getValue().getPending());

        HarnessResult<SteerReceipt> all = ext.cancelPending(session, null);
        assertEquals(1, all.getValue().getCanceled());
        assertEquals(0, all.getValue().getPending());
        assertTrue(ext.pending(session).isEmpty());

        assertEquals(0, ext.cancelPending(InMemoryAgentSession.of(), null).getValue().getCanceled(), "无状态零行为");
    }

    // ==================== 查询 ====================

    @Test
    void pendingListsUnappliedSteersAsSnapshotCopy() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        assertTrue(ext.pending(session).isEmpty(), "无状态时返回空集合（不是 null）");
        assertTrue(ext.pending(null).isEmpty());

        SteerState.of(session).open("run-1");
        HarnessResult<SteerReceipt> a = ext.steer(session, "第一条");
        ext.steer(session, "第二条");

        List<SteerMessage> snapshot = ext.pending(session);
        assertEquals(2, snapshot.size());
        assertEquals(a.getValue().getSteerId(), snapshot.get(0).getId());
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

        assertTrue(ext.steer(session, "a").isOk());
        assertTrue(ext.steer(session, "b").isOk());
        assertTrue(ext.steer(session, "c").hasReason(SteerExtension.REASON_BOX_FULL), "超过 builder 上限应 BOX_FULL");

        assertEquals("TEXT_TOO_LONG", ext.steer(session, "123456789").getReasonOrCode(),
                "超过 builder 文本上限应 TEXT_TOO_LONG");
    }

    @Test
    void defaultConstructorUsesDefaultLimits() {
        SteerExtension ext = new SteerExtension();
        assertEquals(SteerMailbox.MAX_BOX_SIZE, ext.getLimits().getMaxBoxSize());
        assertEquals(SteerMailbox.MAX_TEXT_LENGTH, ext.getLimits().getMaxTextLength());
        assertEquals(SteerMailbox.MAX_ID_LENGTH, ext.getLimits().getMaxIdLength());
    }

    // ==================== 消息协议 ====================

    @Test
    void steerMessagesTextOfRecognizesBothMarks() {
        ChatMessage injected = SteerProtocol.toChatMessage(new SteerMessage("s1", "别改测试"));
        assertEquals("别改测试", SteerProtocol.textOf(injected));

        // metadata 在快照往返中丢失：前缀兜底
        assertEquals("换个思路", SteerProtocol.textOf(ChatMessage.ofUser(SteerProtocol.PREFIX + "换个思路")));

        // 只有 metadata：原样返回
        ChatMessage marked = ChatMessage.ofUser("无前缀");
        marked.addMetadata("source", SteerProtocol.SOURCE);
        assertEquals("无前缀", SteerProtocol.textOf(marked));

        assertNull(SteerProtocol.textOf(ChatMessage.ofUser("普通提问")));
        assertNull(SteerProtocol.textOf(ChatMessage.ofAssistant(SteerProtocol.PREFIX + "x")), "只认 UserMessage");
        assertNull(SteerProtocol.textOf(ChatMessage.ofUser("")));
        assertNull(SteerProtocol.textOf(null));
    }

    // ==================== 状态贡献（contributeSessionStatus）与键登记 ====================

    @Test
    void pendingCountIsContributedToEngineStatus() {
        SteerExtension ext = new SteerExtension();
        HarnessEngine engine = newEngine();
        engine.addExtension(ext);

        AgentSession idle = InMemoryAgentSession.of();
        assertEquals(0, (int) engine.status(idle).get(SteerExtension.STATUS_PENDING));
        assertNull(idle.attrs().get(SteerAttrs.STEER), "读状态不得创建插话状态");

        AgentSession session = runningSession("run-1");
        // 已挂引擎时忙判定走 engine.status：登记一条活跃运行流，否则投递会回滚为 NOT_RUNNING
        org.noear.solon.ai.agent.session.SessionRuns.attach(session, reactor.core.Disposables.single());
        assertTrue(ext.steer(session, "a").isOk());
        assertTrue(ext.steer(session, "b").isOk());
        assertEquals(2, (int) engine.status(session).get(SteerExtension.STATUS_PENDING));

        ext.setEnabled(false);
        assertEquals(2, (int) engine.status(session).get(SteerExtension.STATUS_PENDING), "事实口径不受开关影响");

        ext.cancelPending(session, null);
        assertEquals(0, (int) engine.status(session).get(SteerExtension.STATUS_PENDING));
    }

    @Test
    void stateLivesUnderRegisteredPluginKey() {
        AgentSession session = runningSession("run-1");
        assertTrue(session.attrs().get(SteerAttrs.STEER) instanceof SteerState);
        assertEquals("session.steer", SteerAttrs.STEER, "插件键归 session.<插件> 命名空间");
        assertNull(session.attrs().get("harness.steer"), "旧键不再读写");
    }

    @Test
    void kernelIsPackagePrivate() {
        assertFalse(java.lang.reflect.Modifier.isPublic(SteerMailbox.class.getModifiers()),
                "宿主只能经 SteerExtension 门面");
    }
}
