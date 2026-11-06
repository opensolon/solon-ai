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
package org.noear.solon.ai.harness.extension.loop;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.util.ErrorClassifier;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.scheduling.ScheduledAnno;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 定时任务调度器（插件内部实现，宿主只经 {@link LoopExtension} 门面交互）。
 *
 * <p>职责：任务注册/移除、IJobManager 动态调度、触发守卫（摘除/版本/禁用/忙）、执行器认领、
 * 任务级连续错误处理、tasks.json 持久化（经 {@link LoopStore}）。</p>
 *
 * <p><b>版本与身份</b>：同一 id 的任务可能被 {@link #update} 替换成新版本。触发守卫只放行「当前版本」，
 * 本轮结果记到最新版本上，一次性任务只注销触发它的那个版本（按对象身份，不按 id）。</p>
 *
 * <p><b>摘除后惰性</b>：{@link #unloadAll} 之后不再写盘、不再注册 job、不再放行触发，
 * 直到下一次 {@link #load}。否则在途那一轮结束时会用已清空的内存表覆盖 tasks.json。</p>
 *
 * @author noear
 * @since 3.9.1
 */
final class LoopScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LoopScheduler.class);

    /** 通道化执行器注册项（claim 为 null = 认领全部）。 */
    private static final class ExecutorEntry {
        final String channel;
        final Predicate<String> claim;
        final LoopExecutor handler;

        ExecutorEntry(String channel, Predicate<String> claim, LoopExecutor handler) {
            this.channel = channel;
            this.claim = claim;
            this.handler = handler;
        }
    }

    private final LoopStore store;
    private final IJobManager jobManager;
    private final String jobPrefix;
    private final int maxTasks;
    private final IntSupplier maxErrors;
    private final Predicate<String> sessionAlive;
    private final UnaryOperator<Runnable> triggerWrapper;

    private volatile HarnessEngine engine;
    private volatile LoopExecutor defaultExecutor;

    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();
    private final List<ExecutorEntry> executors = new CopyOnWriteArrayList<>();
    /** 统一存储写锁：所有 mutation 后的落盘串行化。 */
    private final ReentrantLock storeLock = new ReentrantLock();
    /** 全量加载标记（先置位、不反复重试，避免半初始化反复执行）。 */
    private volatile boolean loaded = false;
    /** 已摘除（unloadAll 置位、load 清除）。 */
    private volatile boolean detached = false;

    LoopScheduler(LoopStore store, IJobManager jobManager, String workspaceId, int maxTasks,
                  IntSupplier maxErrors, Predicate<String> sessionAlive, UnaryOperator<Runnable> triggerWrapper) {
        this.store = store;
        this.jobManager = jobManager;
        this.jobPrefix = (workspaceId == null || workspaceId.isEmpty()) ? "" : sanitize(workspaceId) + ":";
        this.maxTasks = maxTasks;
        this.maxErrors = maxErrors;
        this.sessionAlive = sessionAlive;
        this.triggerWrapper = triggerWrapper;
    }

    /** job 名里的工作区段只保留安全字符（与 goal 同口径）。 */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return sb.length() == 0 ? "ws" : sb.toString();
    }

    /** IJobManager 注册名：JobManager 是 JVM 单例，必须带工作区前缀防跨工作区撞名。 */
    String jobName(LoopTask task) {
        return "loop-" + jobPrefix + task.getId();
    }

    void bindEngine(HarnessEngine engine) {
        this.engine = engine;
    }

    void setDefaultExecutor(LoopExecutor executor) {
        this.defaultExecutor = executor;
    }

    void addExecutor(String channel, Predicate<String> claim, LoopExecutor handler) {
        if (handler != null) {
            executors.add(new ExecutorEntry(channel, claim, handler));
        }
    }

    // ==================== 任务注册 ====================

    /**
     * @throws IllegalArgumentException 规格不合法（见 {@link LoopSpec}）
     * @throws IllegalStateException    工作区任务数已达上限
     */
    LoopTask schedule(String sessionId, LoopSpec spec) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return schedule(sessionId, spec.toTask());
    }

    /**
     * @throws IllegalArgumentException cron 无法解析，或已无未来触发时刻
     * @throws IllegalStateException    工作区任务数已达上限
     */
    LoopTask schedule(String sessionId, LoopTask task) {
        if (task.isCronMode()) {
            // 创建入口统一收口：无法解析或已无未来触发时刻的 cron 一律拒绝创建
            LoopCron.requireNextFireTime(task.getCron());
        }
        if (countAllTasks() >= maxTasks) {
            throw new IllegalStateException("Max tasks reached: " + maxTasks);
        }

        List<LoopTask> tasks = sessionTasks.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        cleanExpired(tasks);
        registerJob(sessionId, task, true);
        tasks.add(task);
        persistAll();
        return task;
    }

    boolean remove(String sessionId, String taskId) {
        LoopTask task = get(sessionId, taskId);
        return task != null && removeTask(sessionId, task);
    }

    /**
     * 按对象身份移除（不按 id）：已被新版本替换的旧版本不会连带删掉新版本、摘掉新版本的 job。
     *
     * @return false 表示它已不是当前版本
     */
    private boolean removeTask(String sessionId, LoopTask task) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        // 空列表不摘除：与 schedule 的 computeIfAbsent 并发时摘除会让新任务落进孤儿列表
        if (tasks == null || !tasks.remove(task)) {
            return false;
        }
        LOG.info("Removing loop task '{}' from session '{}'", task.getId(), sessionId);
        task.cancel();
        unregisterJob(task);
        persistAll();
        return true;
    }

    boolean toggle(String sessionId, String taskId) {
        LoopTask t = get(sessionId, taskId);
        return t != null && setEnabled(sessionId, t, !t.isEnabled());
    }

    /** 置为指定启停状态（已是目标状态时不动）。@return false 表示不存在 */
    boolean setEnabled(String sessionId, String taskId, boolean enabled) {
        LoopTask t = get(sessionId, taskId);
        if (t == null) {
            return false;
        }
        return t.isEnabled() == enabled || setEnabled(sessionId, t, enabled);
    }

    private boolean setEnabled(String sessionId, LoopTask t, boolean newEnabled) {
        t.setEnabled(newEnabled);
        if (newEnabled) {
            t.resetErrors();
            registerJob(sessionId, t, false);
        } else {
            unregisterJob(t);
        }
        persistAll();
        return true;
    }

    /**
     * 按补丁产出新版本并替换（新版本与旧版本共享运行锁）。
     *
     * <p>任务正在执行时也允许修改：在途那一轮跑完，结果记到新版本上；共享的运行锁保证新版本不会与它并发。</p>
     *
     * @return 新版本；null 表示不存在
     * @throws IllegalArgumentException 补丁不合法（见 {@link LoopPatch}）
     */
    LoopTask update(String sessionId, String taskId, LoopPatch patch) {
        List<LoopTask> tasks = sessionId == null || taskId == null ? null : sessionTasks.get(sessionId);
        if (tasks == null) return null;

        synchronized (tasks) {
            for (int i = 0; i < tasks.size(); i++) {
                LoopTask t = tasks.get(i);
                if (t.getId().equals(taskId)) {
                    LoopTask newTask = patch.applyTo(t); // 先校验、后替换：非法补丁不动旧版本
                    unregisterJob(t);
                    tasks.set(i, newTask);
                    if (newTask.isEnabled() && !newTask.isCancelled()) {
                        registerJob(sessionId, newTask, false);
                    }
                    persistAll();
                    return newTask;
                }
            }
        }
        return null;
    }

    boolean trigger(String sessionId, String taskId) {
        LoopTask t = get(sessionId, taskId);
        if (t == null) {
            return false;
        }
        RunUtil.parallel(() -> onTrigger(sessionId, t, false));
        return true;
    }

    LoopTask get(String sessionId, String taskId) {
        if (sessionId == null || taskId == null) return null;
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return null;
        synchronized (tasks) {
            for (LoopTask t : tasks) {
                if (t.getId().equals(taskId)) return t;
            }
        }
        return null;
    }

    // ==================== 列表 ====================

    List<LoopTask> list(String sessionId) {
        List<LoopTask> tasks = sessionId == null ? null : sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();
        cleanExpired(tasks);
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    /** 全部会话任务快照（按 sessionId 排序）。 */
    Map<String, List<LoopTask>> listAll() {
        Map<String, List<LoopTask>> result = new LinkedHashMap<>();
        List<String> sessionIds = new ArrayList<>(sessionTasks.keySet());
        Collections.sort(sessionIds);
        for (String sessionId : sessionIds) {
            List<LoopTask> snapshot = list(sessionId);
            if (!snapshot.isEmpty()) {
                result.put(sessionId, snapshot);
            }
        }
        return result;
    }

    /** 会话任务数（只读内存，无副作用；供 onStatus 在会话锁内调用）。 */
    int count(String sessionId) {
        List<LoopTask> tasks = sessionId == null ? null : sessionTasks.get(sessionId);
        return tasks == null ? 0 : tasks.size();
    }

    /** 是否有会自动触发的任务（启用且未取消；已暂停的不算）。 */
    boolean hasActiveTasks() {
        for (List<LoopTask> tasks : sessionTasks.values()) {
            if (tasks == null) continue;
            synchronized (tasks) {
                for (LoopTask t : tasks) {
                    if (t.isEnabled() && !t.isCancelled()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ==================== 批量停止 ====================

    /** 停止会话全部任务并从 tasks.json 删除该会话的记录（删除会话 / /loop stop-all）。 */
    void removeAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            synchronized (tasks) {
                for (LoopTask t : tasks) {
                    t.cancel();
                    unregisterJob(t);
                }
            }
        }
        persistAll();
    }

    /**
     * 卸载全部 job 但保留 tasks.json 记录（扩展摘除 / 工作区关闭）。
     *
     * <p>先置摘除标志再清内存表：之后任何写盘（在途一轮结束、关闭时的报错、宿主的 removeAll）都跳过，
     * 否则会用空快照覆盖文件、丢掉全部任务。</p>
     */
    void unloadAll() {
        detached = true;
        for (String sessionId : new ArrayList<>(sessionTasks.keySet())) {
            try {
                List<LoopTask> tasks = sessionTasks.remove(sessionId);
                if (tasks != null) {
                    synchronized (tasks) {
                        for (LoopTask t : tasks) {
                            unregisterJob(t);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[Loop] unload session {} failed: {}", sessionId, e.getMessage());
            }
        }
        loaded = false; // 允许重新挂接后再次加载
    }

    // ==================== 加载 ====================

    /** 全量加载 tasks.json 并注册全部启用任务（幂等）。 */
    synchronized void load() {
        if (loaded) return;
        loaded = true; // 先置位：即便后续异常也不再重试
        detached = false;
        try {
            Map<String, List<LoopTask>> all = store.load();
            boolean orphanRemoved = false;
            for (Map.Entry<String, List<LoopTask>> entry : new ArrayList<>(all.entrySet())) {
                String sessionId = entry.getKey();
                boolean alive;
                try {
                    alive = sessionAlive.test(sessionId);
                } catch (Throwable e) {
                    alive = true; // 判定失败按存在处理：宁可多留，不可误删
                }
                if (!alive) {
                    all.remove(sessionId);
                    orphanRemoved = true;
                }
            }
            if (orphanRemoved) {
                // ★ 不能调 persistAll()：此时 sessionTasks 尚未填充，以空快照落盘会清空文件
                storeLock.lock();
                try {
                    store.save(all);
                } finally {
                    storeLock.unlock();
                }
            }

            for (Map.Entry<String, List<LoopTask>> entry : all.entrySet()) {
                String sessionId = entry.getKey();
                if (sessionTasks.containsKey(sessionId)) continue; // 已在内存则跳过

                List<LoopTask> alive = new ArrayList<>();
                for (LoopTask t : entry.getValue()) {
                    if (!t.isCancelled()) alive.add(t);
                }
                if (alive.isEmpty()) continue;

                sessionTasks.put(sessionId, Collections.synchronizedList(alive));
                for (LoopTask t : alive) {
                    if (t.isEnabled()) {
                        registerJob(sessionId, t, false);
                    }
                }
            }
            if (!sessionTasks.isEmpty()) {
                LOG.info("Loaded {} sessions of loop tasks from tasks.json", sessionTasks.size());
            }
        } catch (Exception e) {
            LOG.error("Failed to load tasks.json: {}", e.getMessage());
        }
    }

    // ==================== IJobManager 注册 ====================

    private void registerJob(String sessionId, LoopTask task, boolean firstRegistration) {
        ScheduledAnno scheduled;
        if (task.isCronMode()) {
            // 恢复/更新/启用路径不能拒绝创建：cron 已不可触发时直接停用并留痕，保持状态诚实
            try {
                LoopCron.requireNextFireTime(task.getCron());
            } catch (IllegalArgumentException e) {
                task.setEnabled(false);
                LOG.warn("Loop task '{}' disabled, not schedulable: {}", task.getId(), e.getMessage());
                return;
            }
            scheduled = new ScheduledAnno().cron(task.getCron());
        } else {
            long intervalMs = (long) task.getIntervalMinutes() * 60_000L;
            long initialDelay = (firstRegistration && task.isRunNow()) ? 0 : intervalMs;
            scheduled = new ScheduledAnno().fixedDelay(intervalMs).initialDelay(initialDelay);
        }

        if (jobManager == null) {
            LOG.debug("Loop has no job manager; task '{}' kept as metadata only", task.getId());
            return;
        }
        if (detached) {
            return; // 已摘除：不往 JVM 单例 JobManager 里留带着废引擎的 job
        }
        jobManager.jobAdd(jobName(task), scheduled, ctx -> {
            if (!task.isEnabled()) {
                return;
            }
            onTrigger(sessionId, task, true);
        });
    }

    private void unregisterJob(LoopTask task) {
        if (jobManager == null) {
            return;
        }
        String jobName = jobName(task);
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }
    }

    /** job 是否已注册（测试观测口）。 */
    boolean isJobRegistered(LoopTask task) {
        return jobManager != null && jobManager.jobExists(jobName(task));
    }

    // ==================== 触发 ====================

    private void onTrigger(String sessionId, LoopTask task, boolean fromSchedule) {
        // 调度线程无宿主上下文（如日志归属），由宿主注入的包装器统一打标
        Runnable round = () -> doTrigger(sessionId, task, fromSchedule);
        Runnable wrapped = triggerWrapper == null ? round : triggerWrapper.apply(round);
        (wrapped == null ? round : wrapped).run();
    }

    /** 包内可见：供测试直接驱动一轮（不经并行调度）。 */
    void doTrigger(String sessionId, LoopTask task, boolean fromSchedule) {
        if (!checkGuardConditions(sessionId, task)) {
            return;
        }
        if (!task.tryStart()) {
            return;
        }

        try {
            LoopOutcome outcome = execute(sessionId, task.getPrompt());
            // 执行期间任务可能被修改成新版本：结果与错误计数记到最新版本上
            LoopTask target = latestOf(sessionId, task);
            switch (outcome.kind()) {
                case DONE:
                    target.updateLastExecution(outcome.getText());
                    target.incrementIteration();
                    target.resetErrors();
                    persistAll();
                    break;
                case FAILED:
                    handleExecutionError(sessionId, target, new IllegalStateException(
                            outcome.getText() != null ? outcome.getText() : "executor reported failure"));
                    break;
                default:
                    // skip / 无人认领：本轮放弃，不计错误。绝不能记成功：那会让任务看起来一直在跑、实际什么也没做
                    LOG.info("Loop task '{}' skipped for session '{}': {}", task.getId(), sessionId, outcome.getText());
                    break;
            }
        } catch (Exception e) {
            handleExecutionError(sessionId, latestOf(sessionId, task), e);
        } finally {
            // ★ oneShot：调度触发执行完一轮后自动注销（手动 trigger 不消耗配额；异常也消耗）。
            // 只注销触发本轮的那个版本：执行期间被修改过，就保留用户刚改好的新定义
            if (fromSchedule && task.isOneShot()) {
                try {
                    if (removeTask(sessionId, task)) {
                        LOG.info("One-shot loop task '{}' executed, unregistered", task.getId());
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to unregister one-shot task '{}': {}", task.getId(), e.getMessage());
                }
            }
            task.finish();
        }
    }

    /** 同 id 的当前版本；已被删除则返回它自己。 */
    private LoopTask latestOf(String sessionId, LoopTask task) {
        LoopTask current = get(sessionId, task.getId());
        return current != null ? current : task;
    }

    private boolean checkGuardConditions(String sessionId, LoopTask task) {
        if (detached) {
            return false;
        }
        if (task.isCancelled() || get(sessionId, task.getId()) != task) {
            // 已删除，或已被新版本替换（旧版本的重试/迟到回调）：静默放弃。
            // 不能摘 job：job 名按 id 生成，此刻属于新版本
            return false;
        }
        if (!task.isEnabled()) {
            unregisterJob(task);
            return false;
        }
        if (shouldSkipSession(sessionId)) {
            LOG.info("Loop task '{}' skipped: session '{}' not idle", task.getId(), sessionId);
            return false;
        }
        return true;
    }

    /**
     * 任务级连续错误处理：本任务连续失败 ≥ 阈值时停用并告警，否则递增延迟重试。
     *
     * <p>计数挂在任务上（不是调度器上）：一个任务失败不会累计到别的任务头上，成功一轮即清零。</p>
     */
    private void handleExecutionError(String sessionId, LoopTask task, Exception e) {
        String errorType = ErrorClassifier.classifyError(e);
        LOG.error("Loop task '{}' failed [{}]: {}", task.getId(), errorType, e.getMessage());
        task.updateLastExecution("error [" + errorType + "]: " + e.getMessage());
        persistAll();

        if (task.isCancelled()) {
            return;
        }

        int errors = task.recordError();
        int limit = Math.max(1, maxErrors.getAsInt());
        if (errors >= limit) {
            LOG.warn("Loop task '{}' stopped after {} consecutive errors", task.getId(), errors);
            task.setEnabled(false);
            task.resetErrors();
            unregisterJob(task);
            persistAll();
            return;
        }

        long delay = 5L * errors; // 5s, 10s, 15s ...
        LOG.info("Loop task '{}' scheduling error retry in {}s (attempt {}, type={})",
                task.getId(), delay, errors, errorType);
        RunUtil.delay(() -> {
            if (!task.isCancelled() && task.isEnabled()) {
                onTrigger(sessionId, task, false);
            }
        }, delay * 1_000L);
    }

    /** 测试口：直接走错误处理（不经异步重试时序）。 */
    void handleErrorForTest(String sessionId, LoopTask task, Exception e) {
        handleExecutionError(sessionId, task, e);
    }

    // ==================== 执行 ====================

    /**
     * 执行一轮：按注册顺序取首个认领者（谓词命中即认领，结论不穿透）；全部不认领时走缺省执行器。
     *
     * @return 本轮结论（不为 null）；无人认领时为 skip
     * @throws Exception 执行器失败
     */
    LoopOutcome execute(String sessionId, String prompt) throws Exception {
        for (ExecutorEntry entry : executors) {
            if (entry.claim != null) {
                boolean hit;
                try {
                    hit = entry.claim.test(sessionId);
                } catch (Throwable e) {
                    hit = false; // 谓词异常按不命中计
                }
                if (!hit) {
                    continue;
                }
            }
            LoopOutcome outcome = invoke(entry.channel, entry.handler, sessionId, prompt);
            if (entry.channel != null) {
                LOG.debug("[Loop] session '{}' claimed by channel '{}': {}", sessionId, entry.channel, outcome);
            }
            return outcome;
        }
        LoopExecutor fallback = defaultExecutor;
        if (fallback != null) {
            return invoke("default", fallback, sessionId, prompt);
        }
        LOG.warn("[Loop] no executor claimed session '{}', round skipped", sessionId);
        return LoopOutcome.skip("no executor claimed");
    }

    /** 返回 null 是执行器的编程错误：按失败计，让它在 lastResult 与自动停用上可见，而不是静默跳过。 */
    private static LoopOutcome invoke(String channel, LoopExecutor handler, String sessionId, String prompt) throws Exception {
        LoopOutcome outcome = handler.handle(sessionId, prompt);
        if (outcome == null) {
            return LoopOutcome.failed("loop executor '" + channel + "' returned null (must return LoopOutcome)");
        }
        return outcome;
    }

    /**
     * 会话是否应跳过本轮（口径为引擎的 {@code isIdle}：占用或有积压即不空闲）。
     *
     * <p>与 goal 的差异（故意的）：取不到会话时不跳过——宿主的专用会话可能尚未实例化。</p>
     */
    private boolean shouldSkipSession(String sessionId) {
        HarnessEngine e = engine;
        if (e == null) {
            return false;
        }
        try {
            AgentSession session = e.getSession(sessionId);
            if (session == null) {
                return false;
            }
            return !e.isIdle(session);
        } catch (Throwable ex) {
            LOG.debug("[Loop] status check failed for session '{}': {}", sessionId, ex.getMessage());
            return false;
        }
    }

    // ==================== 持久化 ====================

    private void cleanExpired(List<LoopTask> tasks) {
        boolean changed;
        synchronized (tasks) {
            changed = tasks.removeIf(t -> {
                if (t.isCancelled()) {
                    unregisterJob(t);
                    return true;
                }
                return false;
            });
        }
        if (changed) {
            persistAll();
        }
    }

    private int countAllTasks() {
        int count = 0;
        for (List<LoopTask> tasks : sessionTasks.values()) {
            if (tasks != null) count += tasks.size();
        }
        return count;
    }

    /** 全量落盘：内存快照 + 外来条目 → tasks.json（已摘除时跳过：内存表已清空，写下去就是清空文件）。 */
    private void persistAll() {
        if (detached) {
            return;
        }
        storeLock.lock();
        try {
            if (detached) {
                return;
            }
            Map<String, List<LoopTask>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
                List<LoopTask> tasks = entry.getValue();
                if (tasks == null) continue;
                synchronized (tasks) {
                    if (!tasks.isEmpty()) {
                        snapshot.put(entry.getKey(), new ArrayList<>(tasks));
                    }
                }
            }
            store.save(snapshot);
        } finally {
            storeLock.unlock();
        }
    }
}
