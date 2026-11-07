package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.spi.SessionOwnership;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.scope.ScopeGuardInterceptor;
import org.noear.solon.ai.harness.scope.TurnScope;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多租户契约：请求级作用域解析、会话归属租约、引擎静默判定与引擎池
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
                    return TurnScope.builder("tenant-a").tools("read").build();
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

        TurnScope explicit = TurnScope.builder("x").build();
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
        assertEquals(InputOutcome.REJECT_NOT_OWNER, o.getRejectReason());
        assertEquals("node-b", o.getRejectDetail());

        // 程序输入（goal / loop）同样被拒：执行器按「未受理」跳过
        InputOutcome p = engine.handle(HarnessInput.ofProgram(s, "round", "loop"));
        assertEquals(InputOutcome.REJECT_NOT_OWNER, p.getRejectReason());

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
        assertEquals(InputOutcome.REJECT_SCOPE_UNAVAILABLE, o1.getRejectReason());

        HarnessEngine boom = builder().scopeRequired(true)
                .scopeResolver((s, in) -> { throw new IllegalStateException("tenant db down"); })
                .build();
        AgentSession s = InMemoryAgentSession.of();
        InputOutcome o2 = boom.handle(HarnessInput.of(s, "hi"));
        assertEquals(InputOutcome.REJECT_SCOPE_UNAVAILABLE, o2.getRejectReason());
        assertEquals("tenant db down", o2.getRejectDetail());
        assertTrue(boom.isIdle(s), "拒绝后不留占位");

        HarnessEngine ok = builder().scopeRequired(true)
                .scopeResolver((ss, in) -> TurnScope.builder("t").tools("read").build())
                .build();
        InputOutcome o3 = ok.handle(HarnessInput.of(InMemoryAgentSession.of(), "hi"));
        assertEquals(InputOutcome.Kind.TASK, o3.getKind());
        o3.discard();
    }

    @Test
    void defaultOwnershipIsLocal() {
        assertSame(SessionOwnership.LOCAL, builder().build().getSessionOwnership());
    }

    // ---------- 静默与引擎池 ----------

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

    @Test
    void poolCreatesOncePerKeyAndEvictsIdleQuiescent() {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger created = new AtomicInteger();
        Map<String, BusyExtension> exts = new ConcurrentHashMap<>();
        List<String> detached = Collections.synchronizedList(new ArrayList<>());

        HarnessEnginePool pool = HarnessEnginePool.builder(key -> {
                    created.incrementAndGet();
                    BusyExtension ext = new BusyExtension() {
                        @Override
                        public void onDetach(HarnessEngine engine) {
                            detached.add(key);
                        }
                    };
                    exts.put(key, ext);
                    return builder(workspace.resolve(key).toString()).extensionAdd(ext).build();
                })
                .idleTtl(Duration.ofSeconds(10))
                .reapInterval(Duration.ZERO)
                .clock(now::get)
                .build();

        try {
            HarnessEngine a1 = pool.acquire("a");
            HarnessEngine a2 = pool.acquire("a");
            assertSame(a1, a2);
            pool.acquire("b");
            assertEquals(2, created.get());

            // 未到 TTL：不回收
            now.addAndGet(5_000);
            assertEquals(0, pool.evictIdle());

            // 到 TTL，但 a 有后台工作（如活跃 goal）：只回收 b
            now.addAndGet(6_000);
            exts.get("a").busy.set(true);
            assertEquals(1, pool.evictIdle());
            assertEquals(Collections.singleton("a"), pool.keys());
            assertEquals(Collections.singletonList("b"), detached, "回收即 shutdown（扩展收到 onDetach）");

            // a 的后台工作结束后可回收
            exts.get("a").busy.set(false);
            assertEquals(1, pool.evictIdle());
            assertEquals(0, pool.size());
        } finally {
            pool.close();
        }
    }

    @Test
    void acquireDuringEvictionNeverReturnsShutdownEngine() throws Exception {
        AtomicLong now = new AtomicLong(0);
        Set<HarnessEngine> shutdown = ConcurrentHashMap.newKeySet();
        AtomicReference<HarnessEngine> acquired = new AtomicReference<>();
        AtomicReference<Thread> racer = new AtomicReference<>();
        AtomicReference<HarnessEnginePool> poolRef = new AtomicReference<>();

        HarnessEnginePool pool = HarnessEnginePool.builder(key -> {
                    HarnessEngine[] self = new HarnessEngine[1];
                    self[0] = builder(workspace.resolve(key + "-" + System.nanoTime()).toString())
                            .extensionAdd(new HarnessExtension() {
                                @Override
                                public boolean isQuiescent(HarnessEngine e) {
                                    // 回收判定期间，另一线程恰好 acquire 同一个键
                                    if (racer.get() == null) {
                                        Thread t = new Thread(() -> acquired.set(poolRef.get().acquire(key)));
                                        racer.set(t);
                                        t.start();
                                        try {
                                            Thread.sleep(200);
                                        } catch (InterruptedException ignored) {
                                        }
                                    }
                                    return true;
                                }

                                @Override
                                public void onDetach(HarnessEngine e) {
                                    shutdown.add(e);
                                }
                            })
                            .build();
                    return self[0];
                })
                .idleTtl(Duration.ofSeconds(10))
                .reapInterval(Duration.ZERO)
                .clock(now::get)
                .build();
        poolRef.set(pool);

        try {
            pool.acquire("a");
            now.addAndGet(20_000);
            pool.evictIdle();
            racer.get().join(5_000);

            assertNotNull(acquired.get());
            assertFalse(shutdown.contains(acquired.get()), "并发 acquire 拿到的引擎不得是已关闭的");
            assertSame(acquired.get(), pool.getIfPresent("a"));
        } finally {
            pool.close();
        }
    }

    @Test
    void rebuildDuringShutdownKeepsNewEngineLease() throws Exception {
        AtomicLong now = new AtomicLong(0);
        FakeOwnership own = new FakeOwnership("node-a", Collections.emptyMap());
        AgentSession s = InMemoryAgentSession.of();
        AtomicBoolean raced = new AtomicBoolean();
        AtomicReference<Thread> racer = new AtomicReference<>();
        AtomicReference<HarnessEnginePool> poolRef = new AtomicReference<>();

        HarnessEnginePool pool = HarnessEnginePool.builder(key -> builder(workspace.resolve(key + "-" + System.nanoTime()).toString())
                        .sessionOwnership(own)
                        .extensionAdd(new HarnessExtension() {
                            @Override
                            public void onDetach(HarnessEngine e) {
                                // 旧引擎关闭途中：另一线程重建同一个键并认领同一会话
                                if (raced.compareAndSet(false, true)) {
                                    Thread t = new Thread(() -> poolRef.get().acquire(key).handle(HarnessInput.of(s, "again")).discard());
                                    racer.set(t);
                                    t.start();
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                            }
                        })
                        .build())
                .idleTtl(Duration.ofSeconds(10))
                .reapInterval(Duration.ZERO)
                .clock(now::get)
                .build();
        poolRef.set(pool);

        try {
            pool.acquire("a").handle(HarnessInput.of(s, "hello")).discard();
            now.addAndGet(20_000);
            assertEquals(1, pool.evictIdle());
            racer.get().join(5_000);

            assertEquals("node-a", own.ownerOf(s.getSessionId()), "旧引擎关闭不得释放新引擎认领的租约");
            assertNotNull(pool.getIfPresent("a"));
        } finally {
            pool.close();
        }
    }

    @Test
    void poolMaxEnginesEvictsLruQuiescentOrRejects() {
        AtomicLong now = new AtomicLong(0);
        Map<String, BusyExtension> exts = new ConcurrentHashMap<>();
        HarnessEnginePool pool = HarnessEnginePool.builder(key -> {
                    BusyExtension ext = new BusyExtension();
                    exts.put(key, ext);
                    return builder(workspace.resolve(key).toString()).extensionAdd(ext).build();
                })
                .maxEngines(2)
                .reapInterval(Duration.ZERO)
                .clock(now::get)
                .build();

        try {
            pool.acquire("a");
            now.incrementAndGet();
            pool.acquire("b");
            now.incrementAndGet();

            pool.acquire("c"); // a 最久未用且静默 → 腾位
            assertEquals(new HashSet<>(Arrays.asList("b", "c")), pool.keys());

            exts.get("b").busy.set(true);
            exts.get("c").busy.set(true);
            assertThrows(IllegalStateException.class, () -> pool.acquire("d"), "全部忙时拒绝而不是误杀");
        } finally {
            pool.close();
        }
        assertThrows(IllegalStateException.class, () -> pool.acquire("x"), "关闭后不可再取");
    }
}
