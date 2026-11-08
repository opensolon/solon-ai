package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.session.SessionOwnership;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.scope.ScopeGuardInterceptor;
import org.noear.solon.ai.harness.scope.InputScope;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多租户契约：请求级作用域解析、会话归属租约、引擎静默判定（引擎池契约在 host 模块的 HarnessEnginePoolTest）
 */
class MultiTenantContractTest {
    @TempDir
    Path workspace;

    private HarnessEngine.Builder builder(String ws) {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(ws, workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake");
    }

    private HarnessEngine.Builder builder() {
        return builder(workspace.toString());
    }

    // ---------- 作用域 ----------

    @Test
    void resolverRunsForUserAndProgramInputsAndWritesBack() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HarnessEngine engine = builder()
                .scopeResolver((session, input) -> {
                    seen.add(input.isProgram() ? "program" : "user");
                    return InputScope.builder("tenant-a").tools("read").build();
                })
                .build();

        AgentSession s = InMemoryAgentSession.of();
        HarnessInput user = HarnessInput.of(s, "hello");
        InputOutcome o1 = engine.handle(user);
        assertEquals(InputOutcome.Kind.TASK, o1.getKind());
        o1.discard();
        assertNotNull(user.getScope(), "解析结果回写到输入，便于宿主回读");
        assertEquals("tenant-a", user.getScope().getPrincipal());

        HarnessInput program = HarnessInput.ofProgram(s, "goal round", "goal");
        InputOutcome o2 = engine.handle(program);
        o2.discard();
        assertEquals(Arrays.asList("user", "program"), seen, "goal / loop 等程序输入同样经过解析器");
    }

    @Test
    void explicitScopeSkipsResolver() {
        AtomicInteger calls = new AtomicInteger();
        HarnessEngine engine = builder()
                .scopeResolver((session, input) -> {
                    calls.incrementAndGet();
                    return null;
                })
                .build();

        InputScope explicit = InputScope.builder("x").build();
        HarnessInput in = HarnessInput.of(InMemoryAgentSession.of(), "hi").scope(explicit);
        engine.handle(in).discard();
        assertEquals(0, calls.get());
        assertSame(explicit, in.getScope());
    }

    @Test
    void everyAgentCarriesScopeGuardBeforeHitl() {
        HarnessEngine engine = builder().build();
        ReActAgent main = engine.getMainAgent();
        boolean found = main.getConfig().getDefaultOptions().getInterceptors().stream()
                .anyMatch(r -> r.target instanceof ScopeGuardInterceptor && r.index == ScopeGuardInterceptor.INDEX);
        assertTrue(found, "主代理常驻作用域守卫");
        assertTrue(ScopeGuardInterceptor.INDEX < 0, "必须早于 HITL（缺省 0）");
    }

    // ---------- 会话归属 ----------

    static class FakeOwnership implements SessionOwnership {
        final Map<String, String> owners = new ConcurrentHashMap<>();
        final String node;
        final List<String> released = Collections.synchronizedList(new ArrayList<>());

        FakeOwnership(String node, Map<String, String> shared) {
            this.node = node;
            this.owners.putAll(shared);
        }

        @Override
        public boolean tryAcquire(String sessionId) {
            return node.equals(owners.computeIfAbsent(sessionId, k -> node));
        }

        @Override
        public String ownerOf(String sessionId) {
            return owners.get(sessionId);
        }

        @Override
        public void release(String sessionId) {
            released.add(sessionId);
            owners.remove(sessionId, node);
        }
    }

    @Test
    void nonOwnerIsRejectedWithOwnerDetail() {
        AgentSession s = InMemoryAgentSession.of();
        Map<String, String> held = new HashMap<>();
        held.put(s.getSessionId(), "node-b");

        HarnessEngine engine = builder().sessionOwnership(new FakeOwnership("node-a", held)).build();
        InputOutcome o = engine.handle(HarnessInput.of(s, "hello"));
        assertEquals(InputOutcome.Kind.REJECTED, o.getKind());
        assertEquals(InputOutcome.RejectReason.NOT_OWNER, o.getRejectReason());
        assertEquals("node-b", o.getRejectDetail());

        // 程序输入（goal / loop）同样被拒：执行器按「未受理」跳过
        InputOutcome p = engine.handle(HarnessInput.ofProgram(s, "round", "loop"));
        assertEquals(InputOutcome.RejectReason.NOT_OWNER, p.getRejectReason());

        // 不在内存里留下任何运行痕迹：之后由持有者处理
        assertTrue(engine.isIdle(s));
    }

    @Test
    void ownerAcquiresAndReleasesOnRemove() {
        FakeOwnership own = new FakeOwnership("node-a", Collections.emptyMap());
        HarnessEngine engine = builder().sessionOwnership(own).build();
        AgentSession s = InMemoryAgentSession.of();

        InputOutcome o = engine.handle(HarnessInput.of(s, "hello"));
        assertEquals(InputOutcome.Kind.TASK, o.getKind());
        o.discard();
        assertEquals("node-a", own.ownerOf(s.getSessionId()));

        engine.removeSession(s.getSessionId());
        assertEquals(Collections.singletonList(s.getSessionId()), own.released);
        assertNull(own.ownerOf(s.getSessionId()));
    }

    @Test
    void shutdownReleasesOwnedSessions() {
        FakeOwnership own = new FakeOwnership("node-a", Collections.emptyMap());
        HarnessEngine engine = builder().sessionOwnership(own).build();
        AgentSession s1 = InMemoryAgentSession.of();
        AgentSession s2 = InMemoryAgentSession.of();
        engine.handle(HarnessInput.of(s1, "a")).discard();
        engine.handle(HarnessInput.of(s2, "b")).discard();

        engine.shutdown();
        assertEquals(new HashSet<>(Arrays.asList(s1.getSessionId(), s2.getSessionId())), new HashSet<>(own.released),
                "引擎关闭（如被池回收）时释放租约，其它节点可接管");
        assertTrue(own.owners.isEmpty());
    }

    // ---------- 作用域强制 ----------

    @Test
    void requiredScopeRejectsWhenResolverReturnsNullOrThrows() {
        HarnessEngine none = builder().scopeRequired(true).build();
        InputOutcome o1 = none.handle(HarnessInput.of(InMemoryAgentSession.of(), "hi"));
        assertEquals(InputOutcome.Kind.REJECTED, o1.getKind(), "未配解析器：缺失即拒绝");
        assertEquals(InputOutcome.RejectReason.SCOPE_UNAVAILABLE, o1.getRejectReason());

        HarnessEngine boom = builder().scopeRequired(true)
                .scopeResolver((s, in) -> { throw new IllegalStateException("tenant db down"); })
                .build();
        AgentSession s = InMemoryAgentSession.of();
        InputOutcome o2 = boom.handle(HarnessInput.of(s, "hi"));
        assertEquals(InputOutcome.RejectReason.SCOPE_UNAVAILABLE, o2.getRejectReason());
        assertEquals("tenant db down", o2.getRejectDetail());
        assertTrue(boom.isIdle(s), "拒绝后不留占位");

        HarnessEngine ok = builder().scopeRequired(true)
                .scopeResolver((ss, in) -> InputScope.builder("t").tools("read").build())
                .build();
        InputOutcome o3 = ok.handle(HarnessInput.of(InMemoryAgentSession.of(), "hi"));
        assertEquals(InputOutcome.Kind.TASK, o3.getKind());
        o3.discard();
    }

    @Test
    void defaultOwnershipIsLocal() {
        assertSame(SessionOwnership.LOCAL, builder().build().getSessionOwnership());
    }

    // ---------- 静默 ----------

    static class BusyExtension implements HarnessExtension {
        final AtomicBoolean busy = new AtomicBoolean();

        @Override
        public boolean isQuiescent(HarnessEngine engine) {
            return busy.get() == false;
        }
    }

    @Test
    void engineQuiescenceFollowsExtensions() {
        HarnessEngine engine = builder().build();
        assertTrue(engine.isQuiescent());
        assertEquals(0, engine.getActiveRunCount());

        BusyExtension ext = new BusyExtension();
        engine.addExtension(ext);
        ext.busy.set(true);
        assertFalse(engine.isQuiescent());
        ext.busy.set(false);
        assertTrue(engine.isQuiescent());

        engine.addExtension(new HarnessExtension() {
            @Override
            public boolean isQuiescent(HarnessEngine e) {
                throw new IllegalStateException("boom");
            }
        });
        assertFalse(engine.isQuiescent(), "判定异常按不静默处理");
    }
}
