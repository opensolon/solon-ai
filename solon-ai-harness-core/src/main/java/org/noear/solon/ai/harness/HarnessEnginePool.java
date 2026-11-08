/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 引擎池（「每个租户 / 工作区一个引擎」模式）
 *
 * <p>引擎与工作区一一绑定，租户之间需要文件系统隔离时按键各建一个引擎；同一工作区内不同用户 / 角色的差异
 * 用 {@code InputScope}。池负责：按键懒建（同一键并发只建一次）、空闲超时且 {@link HarnessEngine#isQuiescent()}
 * 时回收并 {@link HarnessEngine#shutdown()}、关闭时统一释放。</p>
 *
 * <pre>{@code
 * HarnessEnginePool pool = HarnessEnginePool.builder(tenantId -> HarnessEngine.of(...).build())
 *         .idleTtl(Duration.ofMinutes(30))
 *         .build();
 * HarnessHost host = HarnessHost.builder().engines(pool::get).sink(sink).build();
 * }</pre>
 *
 * <p>注意：有活跃 goal / 定时任务的引擎不会被回收（goal / loop 插件经 {@link HarnessExtension#isQuiescent} 声明），
 * 否则后台工作会随引擎一起消失。进程级 OS 沙盒由宿主启动时统一初始化（SERVER 模式）。</p>
 *
 * <p>本类只用引擎的公开方法（{@code isQuiescent} / {@code shutdown}），与 {@link HarnessHost} 没有代码耦合：
 * 骨架只要一个「租户 → 引擎」函数，池是它最常见的来源。只用 core（L1）的宿主可以按同样的两个方法自行实现。</p>
 *
 * <p>并发约定：刷新与回收（含 {@code shutdown}）在该键的 {@code compute} 里串行完成；<b>构建在锁外</b>——
 * {@code compute} 里只放一个占位 future，由放占位的线程在锁外调用工厂函数，同一个键的其它调用方等这个 future，
 * 慢构建不会卡住其它键。回收只针对已构建完成的条目，且在键锁内关闭旧引擎：旧引擎关完（含释放会话租约）之后，
 * 同一个键才可能放下新的占位，旧引擎不会误释放新引擎认领的租约。构建失败时移除占位，下一次调用重试。
 * 扩展的 {@code onDetach} 里不得再调用本池（会抛 Recursive update）。{@code maxEngines} 是软上限，并发创建时可能短暂超出。</p>
 *
 * <p>重启恢复：池是懒建的，没人访问的租户其 goal / loop 不会自动恢复。接了 {@link HarnessHost} 时用
 * {@link HarnessHost#warmup} 预热（它会先登记后台执行器再恢复 goal）；不接骨架时用 {@link #get} 逐个取
 * （取到即建，但插件只有缺省执行器）。预热的键由宿主维护（例如租户表里「有活跃 goal」的标记）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessEnginePool implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HarnessEnginePool.class);

    private final Function<String, HarnessEngine> factory;
    private final long idleTtlMs;
    private final int maxEngines;
    private final FullPolicy whenFull;
    private final long fullWaitMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> engines = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;
    private volatile boolean closed;

    private static final class Entry {
        final CompletableFuture<HarnessEngine> future = new CompletableFuture<>();
        volatile long lastUsed;

        Entry(long now) {
            this.lastUsed = now;
        }

        /** 已构建完成的引擎（构建中或失败时为 null） */
        HarnessEngine engineOrNull() {
            return future.isDone() && future.isCompletedExceptionally() == false ? future.getNow(null) : null;
        }
    }

    private HarnessEnginePool(Builder b) {
        this.factory = b.factory;
        this.idleTtlMs = b.idleTtl.toMillis();
        this.maxEngines = b.maxEngines;
        this.whenFull = b.whenFull;
        this.fullWaitMs = b.fullWait.toMillis();
        this.clock = b.clock;

        if (b.reapInterval.isZero() || b.reapInterval.isNegative()) {
            this.reaper = null;
        } else {
            this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "harness-engine-pool-reaper");
                t.setDaemon(true);
                return t;
            });
            long ms = b.reapInterval.toMillis();
            this.reaper.scheduleWithFixedDelay(this::evictIdleSafely, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    public static Builder builder(Function<String, HarnessEngine> factory) {
        return new Builder(factory);
    }

    /**
     * 取（或懒建）键对应的引擎，并刷新最近使用时间
     *
     * @throws IllegalStateException 池已关闭，或达到上限且没有可回收的引擎
     */
    public HarnessEngine get(String key) {
        Objects.requireNonNull(key, "key");
        if (closed) {
            throw new IllegalStateException("HarnessEnginePool is closed");
        }

        if (engines.containsKey(key) == false && maxEngines > 0 && engines.size() >= maxEngines) {
            awaitCapacity();
        }

        // 刷新使用时间与回收判定（tryEvict）都在同一个键的 compute 里完成，二者互斥：
        // 要么先被回收、这里新放占位；要么这里先刷新、回收看到新时间放弃
        final Entry[] created = new Entry[1];
        Entry entry = engines.compute(key, (k, old) -> {
            long now = clock.getAsLong();
            if (old != null) {
                old.lastUsed = now;
                return old;
            }
            created[0] = new Entry(now);
            return created[0];
        });

        if (created[0] != null) {
            // 放占位的线程负责构建（锁外）
            try {
                entry.future.complete(Objects.requireNonNull(factory.apply(key), "engine"));
            } catch (Throwable e) {
                entry.future.completeExceptionally(e);
                engines.remove(key, entry); // 不留失败占位，下一次重试
            }
        }

        if (closed && created[0] != null) {
            // 构建期间池被关闭：close 只回收已构建的条目，这里补收
            tryEvict(key, -1L, -1L);
            throw new IllegalStateException("HarnessEnginePool is closed");
        }

        try {
            return entry.future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("HarnessEnginePool build failed: " + key, cause);
        }
    }

    /**
     * 等引擎静默（无在途请求、无活跃后台工作）后移除，超时则放弃（如租户下线前等任务跑完）。
     *
     * @return true = 已移除（或本来就不存在）；false = 超时仍不静默
     * @since 4.2.0
     */
    public boolean removeWhenQuiescent(String key, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + Objects.requireNonNull(timeout, "timeout").toNanos();
        while (true) {
            if (engines.containsKey(key) == false) {
                return true;
            }
            if (tryEvict(key, 0L, -1L)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(Math.min(200L, Math.max(1L, timeout.toMillis())));
        }
    }

    /** 只取不建（不存在返回 null，不刷新使用时间） */
    public HarnessEngine getIfPresent(String key) {
        Entry e = engines.get(key);
        return e == null ? null : e.engineOrNull();
    }

    /** 当前池内的键 */
    public Set<String> keys() {
        return Collections.unmodifiableSet(new HashSet<>(engines.keySet()));
    }

    public int size() {
        return engines.size();
    }

    /**
     * 主动移除并释放（如租户注销）。不判静默：调用方自行保证可以停止。
     */
    public boolean remove(String key) {
        return tryEvict(key, -1L, -1L);
    }

    /**
     * 回收空闲且静默的引擎（后台定时调用；也可由宿主或测试手动触发）
     *
     * @return 本次回收的数量
     */
    public int evictIdle() {
        int count = 0;
        for (String key : new ArrayList<>(engines.keySet())) {
            if (tryEvict(key, idleTtlMs, -1L)) {
                count++;
            }
        }
        return count;
    }

    /** 满额：先回收最久未用且静默的引擎；仍满时按 {@link FullPolicy} 拒绝或等待 */
    private void awaitCapacity() {
        long deadline = System.currentTimeMillis() + (whenFull == FullPolicy.WAIT ? fullWaitMs : 0L);
        while (true) {
            evictLeastRecentlyUsed();
            if (engines.size() < maxEngines) {
                return;
            }
            if (whenFull == FullPolicy.REJECT || System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("HarnessEnginePool is full: " + maxEngines);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HarnessEnginePool wait interrupted", e);
            }
            if (closed) {
                throw new IllegalStateException("HarnessEnginePool is closed");
            }
        }
    }

    /**
     * 满额策略（{@code maxEngines} 达到上限且没有可回收的静默引擎时）
     *
     * @since 4.2.0
     */
    public enum FullPolicy {
        /** 立即拒绝（缺省） */
        REJECT,
        /** 等待其它引擎静默可回收，超过 {@code fullWait} 仍满则拒绝 */
        WAIT
    }

    /** 满额时腾一个位置：最久未用且静默的那个（不看空闲时长） */
    private void evictLeastRecentlyUsed() {
        String victimKey = null;
        long victimUsed = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> kv : engines.entrySet()) {
            Entry e = kv.getValue();
            HarnessEngine engine = e.engineOrNull();
            if (engine != null && e.lastUsed < victimUsed && engine.isQuiescent()) {
                victimKey = kv.getKey();
                victimUsed = e.lastUsed;
            }
        }

        if (victimKey != null) {
            // 选中后若被 acquire 刷新过（lastUsed 变了）就放弃
            tryEvict(victimKey, 0L, victimUsed);
        }
    }

    /**
     * 在键的 compute 内原子地判定、移除并关闭（与 acquire 互斥：关完之前同一个键不会建新引擎）
     *
     * @param minIdleMs    至少空闲多久（-1 = 强制移除：不看空闲也不看静默）
     * @param expectedUsed 期望的 lastUsed（-1 = 不校验）
     */
    private boolean tryEvict(String key, long minIdleMs, long expectedUsed) {
        boolean[] removed = new boolean[1];
        engines.computeIfPresent(key, (k, e) -> {
            HarnessEngine engine = e.engineOrNull();
            if (engine == null) {
                // 构建中：不回收（构建失败的占位由构建线程自己移除）
                return e;
            }
            if (minIdleMs >= 0) {
                if (expectedUsed >= 0 && e.lastUsed != expectedUsed) {
                    return e;
                }
                if (clock.getAsLong() - e.lastUsed < minIdleMs || engine.isQuiescent() == false) {
                    return e;
                }
            }
            shutdownQuietly(k, engine);
            removed[0] = true;
            return null;
        });
        return removed[0];
    }

    private void evictIdleSafely() {
        try {
            evictIdle();
        } catch (Throwable e) {
            LOG.warn("HarnessEnginePool evict failed", e);
        }
    }

    private static void shutdownQuietly(String key, HarnessEngine engine) {
        try {
            engine.shutdown();
        } catch (Throwable e) {
            LOG.warn("HarnessEnginePool shutdown engine failed: {}", key, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (reaper != null) {
            reaper.shutdownNow();
        }

        for (String key : new ArrayList<>(engines.keySet())) {
            remove(key);
        }
    }

    public static final class Builder {
        private final Function<String, HarnessEngine> factory;
        private Duration idleTtl = Duration.ofMinutes(30);
        private Duration reapInterval = Duration.ofMinutes(1);
        private int maxEngines;
        private FullPolicy whenFull = FullPolicy.REJECT;
        private Duration fullWait = Duration.ofSeconds(10);
        private LongSupplier clock = System::currentTimeMillis;

        private Builder(Function<String, HarnessEngine> factory) {
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        /** 空闲多久后可回收（缺省 30 分钟） */
        public Builder idleTtl(Duration idleTtl) {
            this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
            return this;
        }

        /** 后台回收间隔（缺省 1 分钟；0 = 不启动后台回收，由宿主手动 evictIdle） */
        public Builder reapInterval(Duration reapInterval) {
            this.reapInterval = Objects.requireNonNull(reapInterval, "reapInterval");
            return this;
        }

        /** 引擎数上限（缺省 0 = 不限）。达到上限时先回收静默引擎，仍满则拒绝 */
        public Builder maxEngines(int maxEngines) {
            if (maxEngines < 0) {
                throw new IllegalArgumentException("maxEngines must be >= 0");
            }
            this.maxEngines = maxEngines;
            return this;
        }

        /**
         * 满额策略（缺省 {@link FullPolicy#REJECT}）；{@link FullPolicy#WAIT} 时最多等 {@code fullWait}（缺省 10 秒）
         *
         * @since 4.2.0
         */
        public Builder whenFull(FullPolicy whenFull, Duration fullWait) {
            this.whenFull = Objects.requireNonNull(whenFull, "whenFull");
            this.fullWait = Objects.requireNonNull(fullWait, "fullWait");
            return this;
        }

        /** 时钟（测试用） */
        public Builder clock(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public HarnessEnginePool build() {
            return new HarnessEnginePool(this);
        }
    }
}
