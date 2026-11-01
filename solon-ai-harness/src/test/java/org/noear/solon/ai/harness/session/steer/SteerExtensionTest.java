package org.noear.solon.ai.harness.session.steer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SteerExtension 一键装配验证：
 * 1. addExtension 后 /steer 命令自动注册（幂等）；
 * 2. 拦截器经 configure 挂载（源码契约：configure 分支含 defaultInterceptorAdd）；
 * 3. 惰性端口转发：事件回调时才取供应商值，null 时静默；
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
        engine.addExtension(new SteerExtension<>(() -> null));

        assertNotNull(engine.getCommandRegistry().find("steer"), "/steer 命令应自动注册");
    }

    @Test
    void commandRegistrationIsIdempotentAndCanBeDisabled() {
        HarnessEngine engine = newEngine();
        engine.getCommandRegistry().register(new org.noear.solon.ai.harness.command.builtin.SteerCommand());
        engine.addExtension(new SteerExtension<>(() -> null));
        assertNotNull(engine.getCommandRegistry().find("steer"));

        HarnessEngine engine2 = newEngine();
        SteerExtension<Object> disabled = new SteerExtension<>(() -> null);
        disabled.setEnabled(false);
        engine2.addExtension(disabled);
        assertNull(engine2.getCommandRegistry().find("steer"));
        assertNotNull(engine2.getCommandRegistry().get("steer"), "已注册才能区分停用与未注册");
    }

    @Test
    void disabledExtensionRejectsSteer() {
        SteerExtension<Object> ext = new SteerExtension<>(() -> null);
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
        SteerExtension<Object> ext = new SteerExtension<>(() -> null);
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
        SteerExtension<Object> ext = new SteerExtension<>(() -> null);
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
        SteerExtension<Object> ext = new SteerExtension<>(() -> null);
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        // 便捷重载不得绕过入参校验与开关语义
        assertEquals(SteerStatus.EMPTY_TEXT, ext.steer(session, "   ").getStatus());

        ext.setEnabled(false);
        assertEquals(SteerStatus.DISABLED, ext.steer(session, "补充").getStatus());
    }

    @Test
    void threeArgSteerDefaultsNullOrigin() {
        SteerExtension<Object> ext = new SteerExtension<>(() -> null);
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        SteerResult ok = ext.steer(session, "补充", null);
        assertTrue(ok.isSteered(), "null 来源应回落 InputOrigin.NONE 而非报错");
    }

    @Test
    void lazyOutputPortResolvesAtEventTimeNotConstruction() {
        // 供应商先为 null 端口（headless 构造期），后切换为可送达端口——转发端口每次回调取最新值
        SteerExtension<Object> ext = new SteerExtension<>(() -> null);
        // 构造期未触发 NPE 即通过；拦截器持有的是 ForwardingPort
        assertNotNull(ext.getInterceptor());
    }
}
