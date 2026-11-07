/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.ScheduledAnno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Goal 调度器 —— 每个扩展实例<b>一个</b>节拍 job，按会话的到期时间派发轮次（有界并发）。
 *
 * <p>此前是「每个会话一个 5 秒 job」：几千个活跃 goal 就是几千个定时任务，且没有并发上限。
 * 现在是单节拍（{@link #TICK_MS}）扫描到期会话，派发到有界线程池（{@code roundConcurrency}）；
 * 同一会话不并发、轮次结束后才排下一次（等价原 fixedDelay 语义）；满额时本次节拍不再派发（背压），
 * 到期会话留到下一拍。会话存在性与状态只在到期时检查，不在每拍全量回源。</p>
 *
 * <p>调度语义是<b>状态驱动</b>（每轮自判续行），与 loop 按时间表执行不同，二者不共享执行通道。</p>
 *
 * <p><b>job 名带实例序号前缀</b>（{@code goal-<序号>:tick}）：JobManager 是 JVM 单例，同名 job 已存在时
 * 静默沿用旧 job；同一进程可能有多个扩展实例（多引擎 / 重建引擎）。不要改成时间戳（同毫秒创建会重复）。
 * 节拍 job 在第一个会话登记时注册、最后一个会话注销时移除，空闲引擎不占定时任务。</p>
 *
 * <p>多节点：轮次经 {@code handle} 受理，非持有节点被 {@code not_owner} 拒绝后按「未受理」跳过。</p>
 *
 * @author noear
 * @since 3.9.4
 */
class GoalScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(GoalScheduler.class);

    /** 节拍间隔 */
    static final long TICK_MS = 1_000L;
    /** 会话级 goal 轮次间隔（上一轮结束到下一轮开始） */
    static final long ROUND_INTERVAL_MS = 5_000L;
    /** 缺省轮次并发（每个扩展实例） */
    static final int DEFAULT_ROUND_CONCURRENCY = 4;

    private static final AtomicLong JOB_SEQ = new AtomicLong();

    private final GoalRuntime goalRuntime;
    private final IJobManager jobManager;
    private final String jobPrefix;
    private final int roundConcurrency;

    /** 登记中的会话 → 下次到期时间（Long.MAX_VALUE = 本轮执行中） */
    private final Map<String, Long> dueAt = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inflight = new AtomicInteger();
    private final Object tickLock = new Object();
    private volatile boolean tickRegistered;
    private volatile ExecutorService roundPool;
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** 活跃 goal 索引目录（重启恢复用；null = 不持久化） */
    private volatile MountSource indexSource;
    private volatile String indexDir;

    GoalScheduler(GoalRuntime goalRuntime, IJobManager jobManager) {
        this(goalRuntime, jobManager, DEFAULT_ROUND_CONCURRENCY);
    }

    /**
     * @param jobManager       调度管理器（非空）
     * @param roundConcurrency 本实例同时执行的轮次上限（≥ 1）
     */
    GoalScheduler(GoalRuntime goalRuntime, IJobManager jobManager, int roundConcurrency) {
        this.goalRuntime = goalRuntime;
        this.jobManager = jobManager;
        this.roundConcurrency = Math.max(1, roundConcurrency);
        this.jobPrefix = "goal-" + JOB_SEQ.incrementAndGet() + ":";
    }

    /** 节拍 job 名（本实例唯一，所有会话共用一个 job） */
    String tickJobName() {
        return jobPrefix + "tick";
    }

    /** 测试用时钟 */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    int getRoundConcurrency() {
        return roundConcurrency;
    }

    /**
     * 活跃 goal 索引：目录下每个会话一个空标记文件（文件名为编码后的 sessionId），登记时创建、注销时删除；
     * 关闭（stopAll）不动索引，重启后列目录恢复。多个节点共用状态来源时各写各的文件，不会互相覆盖。
     */
    void setIndex(MountSource source, String dir) {
        this.indexSource = source;
        this.indexDir = source == null ? null : source.normalize(dir);
    }

    /** 读取索引中的会话（无索引或读失败返回空） */
    List<String> readIndex() {
        MountSource source = indexSource;
        if (source == null) {
            return Collections.emptyList();
        }
        try {
            if (source.isDirectory(indexDir) == false) {
                return Collections.emptyList();
            }
            List<String> ids = new ArrayList<>();
            for (MountEntry e : source.list(indexDir)) {
                if (e.isDirectory() == false) {
                    ids.add(URLDecoder.decode(e.getName(), "UTF-8"));
                }
            }
            Collections.sort(ids);
            return ids;
        } catch (Throwable e) {
            LOG.warn("Goal index read failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void markIndex(String sessionId, boolean active) {
        MountSource source = indexSource;
        if (source == null) {
            return;
        }
        try {
            String marker = indexDir + "/" + encode(sessionId);
            if (active) {
                source.writeString(marker, "");
            } else {
                source.deleteIfExists(marker);
            }
        } catch (Throwable e) {
            LOG.warn("Goal index update failed for '{}': {}", sessionId, e.getMessage());
        }
    }

    /** 文件名安全编码（含 * 与 . 开头，防路径穿越与通配符） */
    private static String encode(String sessionId) throws UnsupportedEncodingException {
        String s = URLEncoder.encode(sessionId, "UTF-8").replace("*", "%2A");
        return s.startsWith(".") ? "%2E" + s.substring(1) : s;
    }

    /**
     * 登记（或保持）会话的 goal 调度
     *
     * @param runNow 是否下一拍即执行首轮
     */
    void schedule(String sessionId, boolean runNow) {
        if (sessionId == null) return;

        long due = runNow ? 0L : clock.getAsLong() + ROUND_INTERVAL_MS;
        Long prev = dueAt.putIfAbsent(sessionId, due);
        if (prev != null && runNow && prev != Long.MAX_VALUE) {
            dueAt.put(sessionId, 0L);
        }

        ensureTick();
        if (prev == null) {
            markIndex(sessionId, true);
            LOG.info("Goal scheduled: {} ({})", sessionId, tickJobName());
        }
    }

    /** 注销会话的 goal 调度（正在执行的那一轮不打断，结束后不再排期） */
    void unschedule(String sessionId) {
        if (sessionId == null) return;
        if (dueAt.remove(sessionId) != null) {
            markIndex(sessionId, false);
        }
        if (dueAt.isEmpty()) {
            removeTickIfIdle();
        }
    }

    boolean isScheduled(String sessionId) {
        return sessionId != null && dueAt.containsKey(sessionId);
    }

    /** 是否仍有会话的 goal 在调度（宿主闲置判定用） */
    boolean hasScheduledJobs() {
        return !dueAt.isEmpty();
    }

    /** 停止全部 goal 调度（扩展停用 / 摘除时）；线程池保留，可再次登记 */
    void stopAll() {
        dueAt.clear();
        removeTickIfIdle();
    }

    /** 释放线程池（扩展摘除 / 引擎关闭时） */
    void close() {
        stopAll();
        ExecutorService pool = roundPool;
        roundPool = null;
        if (pool != null) {
            pool.shutdown();
        }
    }

    // ---------------- 节拍 ----------------

    private void ensureTick() {
        if (tickRegistered && jobManager.jobExists(tickJobName())) {
            return;
        }

        synchronized (tickLock) {
            if (tickRegistered && jobManager.jobExists(tickJobName())) {
                return;
            }
            jobManager.jobAdd(tickJobName(), new ScheduledAnno().fixedDelay(TICK_MS).initialDelay(0), ctx -> tick());
            tickRegistered = true;
        }
    }

    private void removeTickIfIdle() {
        synchronized (tickLock) {
            if (dueAt.isEmpty() == false) {
                return;
            }
            if (jobManager.jobExists(tickJobName())) {
                jobManager.jobRemove(tickJobName());
            }
            tickRegistered = false;
        }
    }

    /** 一拍：扫描到期会话并派发（包内可见，测试直接驱动） */
    void tick() {
        long now = clock.getAsLong();

        // 公平：到期最早（等得最久）的先派发；背压时顺延的总是后到期的
        List<Map.Entry<String, Long>> due = new ArrayList<>();
        for (Map.Entry<String, Long> e : dueAt.entrySet()) {
            if (e.getValue() <= now) {
                due.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
            }
        }
        due.sort(Map.Entry.comparingByValue());

        for (Map.Entry<String, Long> e : due) {
            if (inflight.get() >= roundConcurrency) {
                return; // 背压：留到下一拍
            }

            String sessionId = e.getKey();
            try {
                dispatchIfReady(sessionId);
            } catch (Throwable ex) {
                LOG.error("Goal tick failed for '{}': {}", sessionId, ex.getMessage());
            }
        }
    }

    private void dispatchIfReady(String sessionId) {
        // 只取已存在的会话：会话已被删（含宿主没走 removeSession）就注销，不重建会话
        AgentSession session = goalRuntime.sessionOrNull(sessionId);
        if (session == null) {
            unschedule(sessionId);
            LOG.info("Goal unscheduled, session not found: {}", sessionId);
            return;
        }

        GoalState gs = goalRuntime.state(session);
        if (gs == null || !gs.getStatus().isActive()) {
            unschedule(sessionId);
            return;
        }

        if (running.add(sessionId) == false) {
            return;
        }

        // 执行中标记：防止下一拍重复派发；已注销时不再登记回来
        if (dueAt.replace(sessionId, Long.MAX_VALUE) == null) {
            running.remove(sessionId);
            return;
        }

        inflight.incrementAndGet();
        try {
            pool().execute(() -> runOnce(sessionId, session));
        } catch (RejectedExecutionException ex) {
            inflight.decrementAndGet();
            running.remove(sessionId);
            dueAt.computeIfPresent(sessionId, (k, v) -> clock.getAsLong() + ROUND_INTERVAL_MS);
        }
    }

    private void runOnce(String sessionId, AgentSession session) {
        try {
            boolean canContinue = goalRuntime.runRound(session);
            if (!canContinue) {
                GoalState latest = goalRuntime.state(session);
                if (latest == null || !latest.getStatus().isActive()) {
                    unschedule(sessionId);
                }
            }
        } catch (Throwable e) {
            LOG.error("Goal round '{}' failed: {}", sessionId, e.getMessage());
        } finally {
            running.remove(sessionId);
            inflight.decrementAndGet();
            // 仍登记（未被注销）时排下一轮
            dueAt.computeIfPresent(sessionId, (k, v) -> clock.getAsLong() + ROUND_INTERVAL_MS);
        }
    }

    private ExecutorService pool() {
        ExecutorService pool = roundPool;
        if (pool == null) {
            synchronized (tickLock) {
                if (roundPool == null) {
                    ThreadPoolExecutor tpe = new ThreadPoolExecutor(roundConcurrency, roundConcurrency,
                            60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, jobPrefix + "round");
                        t.setDaemon(true);
                        return t;
                    });
                    tpe.allowCoreThreadTimeOut(true); // 空闲时不占线程
                    roundPool = tpe;
                }
                pool = roundPool;
            }
        }
        return pool;
    }

    /** 测试用：当前执行中的轮次数 */
    int inflightCount() {
        return inflight.get();
    }
}
