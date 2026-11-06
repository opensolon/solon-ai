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

/**
 * Goal 调度器 —— 把会话级 goal 挂到定时 job 上驱动轮次。
 *
 * <p>调度语义是<b>状态驱动</b>（每轮自判续行），与按时间表独立执行的任务不同，
 * 因此各自持有调度装配，且<b>不存在任何共享执行通道</b>——goal 执行通道在
 * {@link GoalRuntime} 内部，loop 执行通道在 loop 插件内部。</p>
 *
 * <p><b>job 名必须带工作区前缀</b>：JobManager 是 JVM 单例，而会话 ID
 * 并不全局唯一（CLI 会话就叫 {@code cli}、桌面会话是 {@code ws_<时间戳>}），
 * 不加前缀时多个工作区的同名会话会在全局注册表里互相覆盖。</p>
 *
 * @author noear
 * @since 3.9.4
 */
class GoalScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(GoalScheduler.class);

    private final GoalRuntime goalRuntime;
    private final IJobManager jobManager;
    /** job 名前缀（工作区标识，保证跨工作区唯一）。 */
    private final String workspacePrefix;
    /** 会话级 goal 轮次间隔（固定，goal 由状态驱动而非时间表驱动）。 */
    private static final long ROUND_INTERVAL_MS = 5_000L;
    /** 已注册 job 的会话（防重复注册）。 */
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    /**
     * @param jobManager  调度管理器（非空；由 {@link GoalExtension.Builder#jobManager} 提供或缺省取 JobManager 单例）
     * @param workspaceId 工作区标识（用于 job 名前缀；null/空时退化为无前缀，仅单工作区进程安全）
     */
    GoalScheduler(GoalRuntime goalRuntime, IJobManager jobManager, String workspaceId) {
        this.goalRuntime = goalRuntime;
        this.jobManager = jobManager;
        this.workspacePrefix = (workspaceId == null || workspaceId.isEmpty())
                ? "" : sanitize(workspaceId) + ":";
    }

    /** job 名里的工作区段只保留安全字符，避免路径分隔符等进入注册名。 */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return sb.length() == 0 ? "ws" : sb.toString();
    }

    /** goal 的调度任务名（工作区内按会话唯一，跨工作区由前缀区分）。 */
    String jobName(String sessionId) {
        return "goal-" + workspacePrefix + sessionId;
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

        jobManager.jobAdd(jobName, scheduledAnno, ctx -> {
            try {
                AgentSession session = goalRuntime.sessionOf(sessionId);
                if (session == null) return;

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

        scheduled.add(sessionId);
        LOG.info("Goal job registered: {}", jobName);
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

    /** 是否仍有会话的 goal 在调度（工作区闲置判定用；不注销则视为仍在运行）。 */
    boolean hasScheduledJobs() {
        return !scheduled.isEmpty();
    }

    /** 停止全部 goal 调度（工作区关闭时）。 */
    void stopAll() {
        for (String sessionId : new java.util.ArrayList<>(scheduled)) {
            unschedule(sessionId);
        }
    }
}
