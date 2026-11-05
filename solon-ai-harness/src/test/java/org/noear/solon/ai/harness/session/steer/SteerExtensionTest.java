package org.noear.solon.ai.harness.session.steer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SteerExtension 一键装配验证：
 * 1. addExtension 后 /steer 命令自动注册（幂等）；
 * 2. 拦截器经 onRequestAssemble 挂载（源码契约：只挂顶层请求，排除 isDelegated）；
 * 3. 输出走 AgentEvent：无需注入任何宿主端口，单一构造器适配全部宿主；
 * 4. 门面 steer/cancel 中转语义正确。
 */
class SteerExtensionTest {
    @TempDir
    Path workspace;

    private HarnessEngine newEngine() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .build();
    }

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
    void disabledExtensionRejectsSteer() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        ext.setEnabled(false);
        SteerResult r = ext.steer(session, null, null, "补充", null, null);
        assertEquals(SteerStatus.DISABLED, r.getStatus());
        assertEquals(0, r.getQueued());
    }

    @Test
    void requestAssembleMountsInterceptorForTopLevelRequestsOnly() throws Exception {
        // 源码契约：挂载逻辑必须在 onRequestAssemble 上，且以 isDelegated 排除 task/multitask 委派；
        // 不能退回「主代理默认拦截器」——那样面板选中的子代理（同为顶层请求）就命不中，
        // 插话会被判 TURN_CHANGED 后静默降级为排队（无 chatModel 无法真实构建代理，锁源码形态）。
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/org/noear/solon/ai/harness/session/steer/SteerExtension.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("optionsAmend.interceptorAdd(interceptor)"),
                "onRequestAssemble 必须把拦截器挂到请求级 options");
        assertTrue(src.contains("public void onRequestAssemble"),
                "挂载点必须是 onRequestAssemble");
        assertTrue(src.contains("isDelegated == false"),
                "必须排除 task/multitask 委派的子代理任务（临时会话无插话语义）");
        assertFalse(src.contains("agentBuilder.defaultInterceptorAdd(interceptor)"),
                "不得退回主代理默认拦截器（会丢失面板选中子代理的场景）");
    }

    @Test
    void facadeSteerFollowsSameSemanticsAsStaticEntry() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        // 无活跃任务：NOT_RUNNING（与 /steer 命令、Web API 同语义）
        SteerResult r = ext.steer(session, null, null, "补充", null, null);
        assertEquals(SteerStatus.NOT_RUNNING, r.getStatus());

        // 模拟任务运行边界：写入 activeRunId 后可入邮箱
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");
        SteerResult ok = ext.steer(session, null, null, "补充内容", null, null);
        assertTrue(ok.isSteered());
        assertEquals(1, ok.getQueued());

        // 取消仍在邮箱中的插话
        assertTrue(ext.cancel(session, ok.getSteerId()));
    }

    @Test
    void twoArgSteerIsSimplestEquivalentForm() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        // 无活跃任务：NOT_RUNNING（与 6 参形态同语义）
        assertEquals(SteerStatus.NOT_RUNNING, ext.steer(session, "补充").getStatus());

        // 有活跃任务：两参形态直接投递（runId/steerId/busyRecheck 均省略）
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");
        SteerResult ok = ext.steer(session, "补充内容");
        assertTrue(ok.isSteered());
        assertEquals(1, ok.getQueued());
        assertNotNull(ok.getSteerId(), "steerId 应由框架生成");
    }

    @Test
    void twoArgSteerKeepsDisabledAndEmptyTextGuards() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        // 便捷重载不得绕过入参校验与开关语义
        assertEquals(SteerStatus.EMPTY_TEXT, ext.steer(session, "   ").getStatus());

        ext.setEnabled(false);
        assertEquals(SteerStatus.DISABLED, ext.steer(session, "补充").getStatus());
    }

    @Test
    void threeArgSteerDefaultsNullOrigin() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerResult ok = ext.steer(session, "补充", null);
        assertTrue(ok.isSteered(), "null 来源应回落 InputOrigin.NONE 而非报错");
    }

    @Test
    void pendingListsUnappliedSteersAndCancelUsesThoseIds() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        // 邮箱未创建时返回空集合（不是 null），宿主可以直接 for-each
        assertTrue(ext.pending(session).isEmpty());

        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");
        SteerResult a = ext.steer(session, "第一条");
        SteerResult b = ext.steer(session, "第二条");
        assertTrue(a.isSteered());
        assertTrue(b.isSteered());

        // pending 按投递顺序，且带出 cancel 所需的 steerId
        assertEquals(2, ext.pending(session).size());
        assertEquals(a.getSteerId(), ext.pending(session).get(0).getId());
        assertEquals("第二条", ext.pending(session).get(1).getText());

        // 用 pending 里的 id 取消，不依赖投递时保存的返回值（刷新页面场景）
        assertTrue(ext.cancel(session, ext.pending(session).get(0).getId()));
        assertEquals(1, ext.pending(session).size());
    }

    @Test
    void pendingReturnsSnapshotCopy() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerHelper.steer(session, null, null, "补充", null, null);
        List<SteerMessage> snapshot = SteerHelper.pending(session);

        // 清掉真实邮箱，快照不应跟着变（返回的是副本，不是活动视图）
        SteerHelper.steerBox(session).clear();
        assertEquals(1, snapshot.size());
        assertTrue(SteerHelper.pending(session).isEmpty());
    }

    @Test
    void singleConstructorFitsEveryHost() {
        // 输出走 AgentEvent：不存在"漏传端口"的可能，Web/CLI/Desktop 同一构造器
        SteerExtension ext = new SteerExtension();
        assertNotNull(ext.getInterceptor());
    }

    // ==================== 宿主封装度：忙判定自算 ====================

    /**
     * 5 参重载不传 busyRecheck 时，忙判定由引擎缺省口径给出。
     *
     * <p>此前的契约是「宿主必须传 lambda」，而答案就在 {@code engine.status(session)}。
     * 这里锁定「attach 过引擎且会话无活跃流 → 投递被判 NOT_RUNNING 并被回滚」，
     * 证明宿主不再需要自己拼这个判断。</p>
     */
    @Test
    void steerWithoutBusyRecheckUsesEngineDefault() {
        HarnessEngine engine = newEngine();
        SteerExtension ext = new SteerExtension();
        engine.addExtension(ext);

        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        // 引擎里没有活跃流 → 缺省忙判定为 false → offer 后复查回滚
        SteerResult r = ext.steer(session, null, null, "补充", null);
        assertEquals(SteerStatus.NOT_RUNNING, r.getStatus(), "无活跃流时应被回滚");
        assertTrue(ext.pending(session).isEmpty(), "回滚后邮箱不得残留");
    }

    /** 未 attach 引擎（纯单测门面）时忙判定一律放行，避免把所有投递判成 NOT_RUNNING。 */
    @Test
    void detachedExtensionDoesNotBlockSteer() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerResult r = ext.steer(session, null, null, "补充", null);
        assertTrue(r.isSteered(), "无引擎即无忙概念，应放行");
    }

    /** 显式传入的 busyRecheck 仍然优先（高级场景：宿主口径与引擎不同）。 */
    @Test
    void explicitBusyRecheckOverridesEngineDefault() {
        HarnessEngine engine = newEngine();
        SteerExtension ext = new SteerExtension();
        engine.addExtension(ext);

        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        // 显式声明「仍然忙」→ 不复查回滚
        SteerResult r = ext.steer(session, "run-1", null, "补充", null, () -> true);
        assertTrue(r.isSteered());
    }

    // ==================== 严格轮次绑定（原宿主 if 语句收归内核） ====================

    /**
     * {@code steerOnRun} 的 runId 必填：空即 TURN_CHANGED，不生成 steerId、不碰邮箱。
     *
     * <p>Web 端「任务首个事件到达前不放行」原先写死在 Controller 里
     * （ChatWebController 的一条 if），既无类型提示也无从复用。</p>
     */
    @Test
    void steerOnRunRequiresRunId() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerResult blank = ext.steerOnRun(session, "  ", null, "补充", null);
        assertEquals(SteerStatus.TURN_CHANGED, blank.getStatus());
        assertNull(blank.getSteerId(), "被拒绝的投递不得生成 steerId");
        assertTrue(ext.pending(session).isEmpty());

        assertEquals(SteerStatus.TURN_CHANGED, ext.steerOnRun(session, null, null, "补充", null).getStatus());

        // 非严格形态（IM / CLI）同一会话下仍然允许省略 runId
        assertTrue(ext.steer(session, "补充").isSteered());
    }

    /** 严格模式下 runId 与当前轮次不一致仍按 TURN_CHANGED（沿用内核既有语义）。 */
    @Test
    void steerOnRunRejectsForeignRunId() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        assertEquals(SteerStatus.TURN_CHANGED,
                ext.steerOnRun(session, "run-2", null, "补充", null).getStatus());
        assertTrue(ext.steerOnRun(session, "run-1", null, "补充", null).isSteered());
    }

    // ==================== 容量可配 ====================

    /** builder 设置的容量上限生效：第 3 条返回 BOX_FULL。 */
    @Test
    void builderLimitsAreEnforced() {
        SteerExtension ext = SteerExtension.builder().maxBoxSize(2).maxTextLength(8).build();
        assertEquals(2, ext.getLimits().getMaxBoxSize());

        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        assertTrue(ext.steer(session, "a").isSteered());
        assertTrue(ext.steer(session, "b").isSteered());
        assertTrue(ext.steer(session, "c").isBoxFull(), "超过 builder 上限应 BOX_FULL");

        assertEquals(SteerStatus.TEXT_TOO_LONG, ext.steer(session, "123456789").getStatus(),
                "超过 builder 文本上限应 TEXT_TOO_LONG");
    }

    /** 缺省构造器等价于 SteerLimits.DEFAULT。 */
    @Test
    void defaultConstructorUsesDefaultLimits() {
        SteerExtension ext = new SteerExtension();
        assertEquals(SteerHelper.MAX_BOX_SIZE, ext.getLimits().getMaxBoxSize());
        assertEquals(SteerHelper.MAX_TEXT_LENGTH, ext.getLimits().getMaxTextLength());
        assertEquals(SteerHelper.MAX_ID_LENGTH, ext.getLimits().getMaxIdLength());
    }

    // ==================== 批量取消 / 清空 ====================

    /** cancelAll 只统计真正从邮箱移除的条目，已被消费的不计入。 */
    @Test
    void cancelAllCountsOnlyActuallyRemoved() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerResult a = ext.steer(session, "第一条");
        SteerResult b = ext.steer(session, "第二条");
        // 模拟消费线程已取走 a
        SteerHelper.steerBox(session).removeIf(m -> m.getId().equals(a.getSteerId()));

        int canceled = ext.cancelAll(session, java.util.Arrays.asList(a.getSteerId(), b.getSteerId()));
        assertEquals(1, canceled, "已被消费的不应计入");
        assertTrue(ext.pending(session).isEmpty());
    }

    /** clearPending 交还全部条目且清空邮箱，不做任何降级决定。 */
    @Test
    void clearPendingReturnsItemsAndEmptiesBox() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");

        ext.steer(session, "第一条");
        ext.steer(session, "第二条");

        List<SteerMessage> cleared = ext.clearPending(session);
        assertEquals(2, cleared.size());
        assertEquals("第一条", cleared.get(0).getText(), "按投递顺序返回");
        assertTrue(ext.pending(session).isEmpty());
        assertTrue(ext.clearPending(session).isEmpty(), "空邮箱返回空集合而非 null");
    }

    /** beginTurn 与 handover 是同一语义（开流前义务的显式命名）。 */
    @Test
    void beginTurnIsHandoverAlias() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, "run-1");
        ext.steer(session, "残留插话");

        SteerExtension.SteerHandover handed = ext.beginTurn(session);
        assertEquals(1, handed.getQueued().size(), "残留插话应转入排队");
        assertNull(ext.activeRunId(session), "轮次标识应被清空");
    }
}
