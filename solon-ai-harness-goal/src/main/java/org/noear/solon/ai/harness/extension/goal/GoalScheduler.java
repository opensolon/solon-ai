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
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.ScheduledAnno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Goal 调度器 —— 把会话级 goal 挂到定时 job 上驱动轮次。
 *
 * <p>调度语义是<b>状态驱动</b>（每轮自判续行），与按时间表独立执行的任务不同，
 * 因此各自持有调度装配，且<b>不存在任何共享执行通道</b>——goal 执行通道在
 * {@link GoalRuntime} 内部，loop 执行通道在 loop 插件内部。</p>
 *
 * <p><b>job 名带实例序号前缀</b>（{@code goal-<序号>:<sessionId>}）：JobManager 是 JVM 单例，
 * 同名 job 已存在时静默沿用旧 job（新回调被丢弃）；而会话 ID 的格式由宿主决定、不保证全局唯一，
 * 同一会话也可能被两个扩展实例调度（如重建引擎时旧实例未卸载干净）。序号只保证本进程内唯一：
 * job 只在内存、不跨重启，job 名也不落盘，重启后从头计数不影响正确性。不要改成时间戳（同毫秒创建会重复）。</p>
 *
 * @author noear
 * @since 3.9.4
 */
class GoalScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(GoalScheduler.class);

    private final GoalRuntime goalRuntime;
    private final IJobManager jobManager;
    /** 调度器实例序号（job 名前缀用）。 */
    private static final AtomicLong JOB_SEQ = new AtomicLong();
    /** job 名前缀（本实例独有）。 */
    private final String jobPrefix;
    /** 会话级 goal 轮次间隔（固定，goal 由状态驱动而非时间表驱动）。 */
    private static final long ROUND_INTERVAL_MS = 5_000L;
    /** 已注册 job 的会话（防重复注册）。 */
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    /**
     * @param jobManager 调度管理器（非空；由 {@link GoalExtension.Builder#jobManager} 提供或缺省取 JobManager 单例）
     */
    GoalScheduler(GoalRuntime goalRuntime, IJobManager jobManager) {
        this.goalRuntime = goalRuntime;
        this.jobManager = jobManager;
        this.jobPrefix = "goal-" + JOB_SEQ.incrementAndGet() + ":";
    }

    /** goal 的调度任务名（本实例内按会话唯一，实例之间由前缀区分）。 */
    String jobName(String sessionId) {
        return jobPrefix + sessionId;
    }

    /**
     * 注册（或复用）会话的 goal job。
     *
     * @param sessionId 会话 ID
     * @param runNow    是否立即触发首轮
     */
    void schedule(String sessionId, boolean runNow) {
        if (sessionId == null) return;
        String jobName = jobName(sessionId);
        if (scheduled.contains(sessionId) && jobManager.jobExists(jobName)) {
            return;
        }

        ScheduledAnno scheduledAnno = new ScheduledAnno()
                .fixedDelay(ROUND_INTERVAL_MS)
                .initialDelay(runNow ? 0 : ROUND_INTERVAL_MS);

        // 先登记再注册：runNow 时 job 可能立即触发，而触发体会校验登记
        scheduled.add(sessionId);
        try {
            addJob(jobName, scheduledAnno, sessionId);
        } catch (RuntimeException e) {
            scheduled.remove(sessionId);
            throw e;
        }

        LOG.info("Goal job registered: {}", jobName);
    }

    private void addJob(String jobName, ScheduledAnno scheduledAnno, String sessionId) {
        jobManager.jobAdd(jobName, scheduledAnno, ctx -> {
            try {
                if (scheduled.contains(sessionId) == false) {
                    return; // 已注销（如会话被删），挡住注销前已在排队的那一次触发
                }

                // 只取已存在的会话：会话已被删（含宿主没走 removeSession 的情况）就注销自己，不重建会话
                AgentSession session = goalRuntime.sessionOrNil(sessionId);
                if (session == null) {
                    unschedule(sessionId);
                    LOG.info("Goal job removed, session not found: {}", jobName);
                    return;
                }

                GoalState gs = goalRuntime.state(session);
                if (gs == null || !gs.getStatus().isActive()) {
                    unschedule(sessionId);
                    return;
                }

                boolean canContinue = goalRuntime.runRound(session);
                if (!canContinue) {
                    GoalState latest = goalRuntime.state(session);
                    if (latest == null || !latest.getStatus().isActive()) {
                        unschedule(sessionId);
                    }
                }
            } catch (Throwable e) {
                LOG.error("Goal job '{}' failed: {}", jobName, e.getMessage());
            }
        });
    }

    /** 注销会话的 goal job。 */
    void unschedule(String sessionId) {
        if (sessionId == null) return;
        String jobName = jobName(sessionId);
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }
        scheduled.remove(sessionId);
    }

    /** 是否仍有会话的 goal 在调度（宿主闲置判定用；不注销则视为仍在运行）。 */
    boolean hasScheduledJobs() {
        return !scheduled.isEmpty();
    }

    /** 停止全部 goal 调度（扩展摘除 / 引擎关闭时）。 */
    void stopAll() {
        for (String sessionId : new java.util.ArrayList<>(scheduled)) {
            unschedule(sessionId);
        }
    }
}
