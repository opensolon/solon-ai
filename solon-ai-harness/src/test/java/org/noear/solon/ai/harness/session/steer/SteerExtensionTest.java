package org.noear.solon.ai.harness.session.steer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SteerExtension 一键装配验证：
 * 1. addExtension 后 /steer 命令自动注册（幂等）；
 * 2. 拦截器经 configure 挂载（源码契约：configure 分支含 defaultInterceptorAdd）；
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
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        ext.setEnabled(false);
        SteerResult r = ext.steer(session, null, null, "补充", null, null);
        assertEquals(SteerStatus.DISABLED, r.getStatus());
        assertEquals(0, r.getQueued());
    }

    @Test
    void configureMountsInterceptorAsDefaultForMainAgent() throws Exception {
        // 源码契约：挂载逻辑必须存在且只针对主代理（无 chatModel 无法真实构建代理，锁源码形态）
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/org/noear/solon/ai/harness/session/steer/SteerExtension.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("agentBuilder.defaultInterceptorAdd(interceptor)"),
                "configure 必须把拦截器挂为主代理默认拦截器");
        assertTrue(src.contains("AgentDefinition.AGENT_MAIN.equals(agentName)"),
                "只挂主代理");
    }

    @Test
    void facadeSteerFollowsSameSemanticsAsStaticEntry() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        // 无活跃任务：NOT_RUNNING（与 /steer 命令、Web API 同语义）
        SteerResult r = ext.steer(session, null, null, "补充", null, null);
        assertEquals(SteerStatus.NOT_RUNNING, r.getStatus());

        // 模拟任务运行边界：写入 activeRunId 后可入邮箱
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");
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
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");
        SteerResult ok = ext.steer(session, "补充内容");
        assertTrue(ok.isSteered());
        assertEquals(1, ok.getQueued());
        assertNotNull(ok.getSteerId(), "steerId 应由框架生成");
    }

    @Test
    void twoArgSteerKeepsDisabledAndEmptyTextGuards() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        // 便捷重载不得绕过入参校验与开关语义
        assertEquals(SteerStatus.EMPTY_TEXT, ext.steer(session, "   ").getStatus());

        ext.setEnabled(false);
        assertEquals(SteerStatus.DISABLED, ext.steer(session, "补充").getStatus());
    }

    @Test
    void threeArgSteerDefaultsNullOrigin() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerResult ok = ext.steer(session, "补充", null);
        assertTrue(ok.isSteered(), "null 来源应回落 InputOrigin.NONE 而非报错");
    }

    @Test
    void pendingListsUnappliedSteersAndCancelUsesThoseIds() {
        SteerExtension ext = new SteerExtension();
        AgentSession session = InMemoryAgentSession.of();

        // 邮箱未创建时返回空集合（不是 null），宿主可以直接 for-each
        assertTrue(ext.pending(session).isEmpty());

        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");
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
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerInterceptor.steer(session, null, null, "补充", null, null);
        List<SteerMessage> snapshot = SteerInterceptor.pending(session);

        // 清掉真实邮箱，快照不应跟着变（返回的是副本，不是活动视图）
        SteerInterceptor.steerBox(session).clear();
        assertEquals(1, snapshot.size());
        assertTrue(SteerInterceptor.pending(session).isEmpty());
    }

    @Test
    void singleConstructorFitsEveryHost() {
        // 输出走 AgentEvent：不存在"漏传端口"的可能，Web/CLI/Desktop 同一构造器
        SteerExtension ext = new SteerExtension();
        assertNotNull(ext.getInterceptor());
    }
}
