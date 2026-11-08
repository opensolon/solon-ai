package features.ai.harness.all;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.HarnessEnginePool;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.session.SessionOwnership;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎池契约（4.2.0 从 core 迁入 host，原在 core 的 MultiTenantContractTest / SaasContractTest）：
 * 按键懒建、空闲且静默才回收、回收与并发取用互斥、旧引擎关闭不误释放新引擎租约、构建在锁外、满额策略。
 */
class HarnessEnginePoolTest {
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

    /** 可切换「有后台工作」的扩展（模拟活跃 goal / 定时任务） */
    static class BusyExtension implements HarnessExtension {
        final AtomicBoolean busy = new AtomicBoolean();

        @Override
        public boolean isQuiescent(HarnessEngine engine) {
            return busy.get() == false;
        }
    }

    /** 进程内租约桩 */
    static class FakeOwnership implements SessionOwnership {
        final Map<String, String> owners = new ConcurrentHashMap<>();
        final String node;

        FakeOwnership(String node) {
            this.node = node;
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
            owners.remove(sessionId, node);
        }
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
            HarnessEngine a1 = pool.get("a");
            HarnessEngine a2 = pool.get("a");
            assertSame(a1, a2);
            pool.get("b");
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

        HarnessEnginePool pool = HarnessEnginePool.builder(key -> builder(workspace.resolve(key + "-" + System.nanoTime()).toString())
                        .extensionAdd(new HarnessExtension() {
                            @Override
                            public boolean isQuiescent(HarnessEngine e) {
                                // 回收判定期间，另一线程恰好 acquire 同一个键
                                if (racer.get() == null) {
                                    Thread t = new Thread(() -> acquired.set(poolRef.get().get(key)));
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
                        .build())
                .idleTtl(Duration.ofSeconds(10))
                .reapInterval(Duration.ZERO)
                .clock(now::get)
                .build();
        poolRef.set(pool);

        try {
            pool.get("a");
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
        FakeOwnership own = new FakeOwnership("node-a");
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
                                    Thread t = new Thread(() -> poolRef.get().get(key).handle(HarnessInput.of(s, "again")).discard());
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
            pool.get("a").handle(HarnessInput.of(s, "hello")).discard();
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
            pool.get("a");
            now.incrementAndGet();
            pool.get("b");
            now.incrementAndGet();

            pool.get("c"); // a 最久未用且静默 → 腾位
            assertEquals(new HashSet<>(Arrays.asList("b", "c")), pool.keys());

            exts.get("b").busy.set(true);
            exts.get("c").busy.set(true);
            assertThrows(IllegalStateException.class, () -> pool.get("d"), "全部忙时拒绝而不是误杀");
        } finally {
            pool.close();
        }
        assertThrows(IllegalStateException.class, () -> pool.get("x"), "关闭后不可再取");
    }

    @Test
    void poolBuildsOutsideLockAndRetriesAfterFailure() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicInteger failOnce = new AtomicInteger();
        HarnessEnginePool pool = HarnessEnginePool.builder(key -> {
            if ("slow".equals(key)) {
                slowStarted.countDown();
                try {
                    releaseSlow.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if ("flaky".equals(key) && failOnce.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }
            return builder().build();
        }).reapInterval(Duration.ZERO).build();

        try {
            Thread slow = new Thread(() -> pool.get("slow"));
            slow.start();
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));

            long t0 = System.nanoTime();
            assertNotNull(pool.get("fast"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000, "慢构建不阻塞其它键");
            assertNull(pool.getIfPresent("slow"), "构建中不可见");
            assertFalse(pool.remove("slow"), "构建中不回收");

            releaseSlow.countDown();
            slow.join(5000);
            assertNotNull(pool.getIfPresent("slow"));

            assertThrows(IllegalStateException.class, () -> pool.get("flaky"));
            assertNull(pool.getIfPresent("flaky"), "失败不留占位");
            assertNotNull(pool.get("flaky"), "下一次重试成功");

            assertNotNull(pool.get("w1"), "重置后逐个 get 即可建键（预热由宿主骨架负责）");
            assertNotNull(pool.get("w2"));
            assertTrue(pool.removeWhenQuiescent("w1", Duration.ofSeconds(2)));
            assertNull(pool.getIfPresent("w1"));
        } finally {
            releaseSlow.countDown();
            pool.close();
        }
    }

    @Test
    void poolWhenFullRejectOrWait() throws Exception {
        HarnessEnginePool reject = HarnessEnginePool.builder(k -> builder().build())
                .maxEngines(1).reapInterval(Duration.ZERO).build();
        HarnessEnginePool wait = HarnessEnginePool.builder(k -> builder().build())
                .maxEngines(1).reapInterval(Duration.ZERO)
                .whenFull(HarnessEnginePool.FullPolicy.WAIT, Duration.ofMillis(300)).build();
        try {
            // 静默引擎可被 LRU 腾位：两种策略都能放下新键
            reject.get("a");
            assertNotNull(reject.get("b"));
            assertNull(reject.getIfPresent("a"));

            wait.get("a");
            long t0 = System.nanoTime();
            assertNotNull(wait.get("b"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 300, "可腾位时不等待");
        } finally {
            reject.close();
            wait.close();
        }
    }
}
