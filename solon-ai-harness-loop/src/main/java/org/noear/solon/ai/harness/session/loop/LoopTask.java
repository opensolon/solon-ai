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
package org.noear.solon.ai.harness.session.loop;

import org.noear.snack4.ONode;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话定时任务模型（/loop、loop_* 工具、宿主 Web 端共用）。
 *
 * <p>本类只承载「按时间表触发的独立任务」：固定间隔（{@code intervalMinutes}）或 cron。
 * 会话目标（goal）是另一个插件，二者不共享模型。</p>
 *
 * <p><b>对宿主完全只读</b>：新建用 {@link LoopSpec}，修改用 {@link LoopPatch}，启停/删除/触发走
 * {@link LoopExtension} 门面。宿主拿到的 LoopTask 只用来读（含 {@link #toMap()} 标准展示视图）。</p>
 *
 * <p><b>扩展字段（extras）</b>：宿主可在任务上挂自有元数据（如 soloncode 的自动任务覆盖层
 * {@code "automation"}），经 {@link LoopSpec#extra}/{@link LoopPatch#extra} 写入、{@link #getExtra} 读取。
 * 反序列化时所有不认识的顶层字段一律收进 extras 并原样写回——插件不认识宿主概念，但绝不吞掉宿主数据。</p>
 *
 * <p><b>运行锁随任务身份</b>：修改产出的新版本与旧版本共享同一把运行锁与错误计数——
 * 修改正在执行的任务时，新版本不会与在途那一轮并发执行。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class LoopTask {

    static final int MIN_INTERVAL = 1;
    static final int MAX_INTERVAL = 1440; // 24h

    /** 由本类解析的顶层字段（其余字段进 extras）。{@code sessionId} 属存储层定位键。 */
    static final Set<String> RESERVED_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "id", "prompt", "intervalMinutes", "cron", "createdAt", "autoInterval",
            "cancelled", "enabled", "lastResult", "lastExecutedAt", "currentIteration",
            "runNow", "oneShot", "sessionId")));

    // ---- 核心调度字段 ----
    private final String id;
    private final String prompt;
    private final int intervalMinutes;
    private final String cron;
    private final Instant createdAt;
    private final boolean autoInterval;
    private final boolean runNow;            // 注册后立即执行首次（initialDelay=0）

    // ---- 运行时状态 ----
    /** 运行锁（瞬态；同一 id 的各版本共享，见 copyWithUpdate）。 */
    private final AtomicBoolean running;
    private volatile boolean cancelled;
    private volatile String lastResult;
    private volatile Instant lastExecutedAt;
    private volatile int currentIteration;
    private volatile boolean enabled = true;
    private volatile boolean oneShot;        // 一次性任务：调度触发执行一轮后自动注销

    /** 连续失败计数（任务级瞬态，不持久化；成功一轮即清零；同一 id 的各版本共享）。 */
    private final AtomicInteger errorStreak;

    /** 宿主扩展字段（保持插入顺序，保证往返序列化稳定）。 */
    private final Map<String, ONode> extras = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 包内构造（宿主新建任务用 {@link LoopSpec}）；间隔静默收敛到合法区间。 */
    LoopTask(String prompt, int intervalMinutes, String cron, boolean runNow) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.prompt = prompt;
        // 间隔收敛在 [MIN_INTERVAL, MAX_INTERVAL]，不存在 0 间隔（cron 模式忽略该值）
        this.intervalMinutes = clampInterval(intervalMinutes);
        this.cron = cron;
        this.createdAt = Instant.now();
        this.autoInterval = false;
        this.runNow = runNow;
        this.currentIteration = 0;
        this.enabled = true;
        this.running = new AtomicBoolean();
        this.errorStreak = new AtomicInteger();
    }

    private LoopTask(String id, String prompt, int intervalMinutes, String cron,
                     Instant createdAt, boolean autoInterval, boolean enabled, boolean runNow,
                     boolean cancelled, String lastResult, Instant lastExecutedAt, int currentIteration,
                     AtomicBoolean running, AtomicInteger errorStreak) {
        this.running = running;
        this.errorStreak = errorStreak;
        this.id = id;
        this.prompt = prompt;
        this.intervalMinutes = intervalMinutes;
        this.cron = cron;
        this.createdAt = createdAt;
        this.autoInterval = autoInterval;
        this.enabled = enabled;
        this.runNow = runNow;
        this.cancelled = cancelled;
        this.lastResult = lastResult;
        this.lastExecutedAt = lastExecutedAt;
        this.currentIteration = currentIteration;
    }

    private static int clampInterval(int minutes) {
        return Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, minutes));
    }

    /**
     * 基于当前任务复制出一份更新后的任务定义，保留任务身份、运行时状态（含启停）与扩展字段（宿主用 {@link LoopPatch}）。
     *
     * <p>新旧版本共享运行锁与错误计数：在途那一轮结束时释放的是同一把锁。</p>
     */
    LoopTask copyWithUpdate(String prompt, int intervalMinutes, String cron, Boolean runNow) {
        LoopTask task = new LoopTask(
                this.id,
                prompt,
                clampInterval(intervalMinutes),
                cron,
                this.createdAt,
                this.autoInterval,
                this.enabled,
                runNow != null ? runNow : this.runNow,
                this.cancelled,
                this.lastResult,
                this.lastExecutedAt,
                this.currentIteration,
                this.running,
                this.errorStreak
        );
        task.oneShot = this.oneShot;
        synchronized (extras) {
            task.extras.putAll(this.extras);
        }
        return task;
    }

    // ==================== 读取 ====================

    public String getId() { return id; }
    public String getPrompt() { return prompt; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public String getCron() { return cron; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isRunNow() { return runNow; }
    public boolean isRunning() { return running.get(); }
    public boolean isCancelled() { return cancelled; }
    public String getLastResult() { return lastResult; }
    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public int getCurrentIteration() { return currentIteration; }
    public boolean isEnabled() { return enabled; }
    public boolean isOneShot() { return oneShot; }

    /** 是否为 cron 模式 */
    public boolean isCronMode() {
        return cron != null && !cron.isEmpty();
    }

    // ==================== 扩展字段 ====================

    /** 宿主扩展字段（不存在为 null）。只读：修改请走 {@link LoopPatch#extra}。 */
    public ONode getExtra(String key) {
        return key == null ? null : extras.get(key);
    }

    /** @throws IllegalArgumentException key 为空或与核心字段同名 */
    static void requireExtraKey(String key) {
        if (key == null || key.isEmpty() || RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Invalid extra key: " + key);
        }
    }

    /** 写入扩展字段（value 为 null 表示删除）。包内：宿主经 LoopSpec/LoopPatch 写入。 */
    void putExtra(String key, ONode value) {
        requireExtraKey(key);
        if (value == null) {
            extras.remove(key);
        } else {
            extras.put(key, value);
        }
    }

    // ==================== 运行时状态变更（包内：只由调度器驱动） ====================

    /** 尝试获取执行权（CAS）。@return true 表示成功 */
    boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    /** 释放执行权 */
    void finish() {
        running.set(false);
    }

    void cancel() {
        cancelled = true;
    }

    void updateLastExecution(String result) {
        this.lastResult = result;
        this.lastExecutedAt = Instant.now();
    }

    void incrementIteration() {
        currentIteration++;
    }

    void setEnabled(boolean enabled) { this.enabled = enabled; }

    void setOneShot(boolean oneShot) { this.oneShot = oneShot; }

    /** 连续失败数（瞬态）。 */
    int getErrorStreak() { return errorStreak.get(); }

    int recordError() { return errorStreak.incrementAndGet(); }

    void resetErrors() { errorStreak.set(0); }

    // ==================== 展示视图 ====================

    /**
     * 标准展示视图（不含扩展字段），宿主列表/详情接口直接用，再按需追加自己的字段（如 type、sessionId）。
     *
     * <p>字段：id、prompt、intervalMinutes、cron（仅 cron 模式）、oneShot、runNow、enabled、cancelled、running、
     * currentIteration、lastResult（有则出）、lastExecutedAt（ISO-8601，有则出）。</p>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("prompt", prompt);
        item.put("intervalMinutes", intervalMinutes);
        if (isCronMode()) item.put("cron", cron);
        item.put("oneShot", oneShot);
        item.put("runNow", runNow);
        item.put("enabled", enabled);
        item.put("cancelled", cancelled);
        item.put("running", running.get());
        item.put("currentIteration", currentIteration);
        if (lastResult != null) item.put("lastResult", lastResult);
        if (lastExecutedAt != null) item.put("lastExecutedAt", lastExecutedAt.toString());
        return item;
    }

    // ==================== 序列化 ====================

    /** 序列化为 ONode（不含 sessionId，定位键由存储层写入）。 */
    public ONode toONode() {
        ONode node = new ONode();
        node.set("id", id);
        node.set("prompt", prompt);
        node.set("intervalMinutes", intervalMinutes);
        if (cron != null) {
            node.set("cron", cron);
        }
        node.set("createdAt", createdAt.toString());
        node.set("autoInterval", autoInterval);
        node.set("cancelled", cancelled);
        node.set("enabled", enabled);
        // running 是瞬态运行时锁，不持久化

        if (lastResult != null) {
            node.set("lastResult", lastResult);
        }
        if (lastExecutedAt != null) {
            node.set("lastExecutedAt", lastExecutedAt.toString());
        }
        node.set("currentIteration", currentIteration);

        if (runNow) node.set("runNow", true);
        if (oneShot) node.set("oneShot", true);   // 缺省 false，旧 JSON 兼容

        // 宿主扩展字段原样写回（不认识也不丢）
        synchronized (extras) {
            for (Map.Entry<String, ONode> e : extras.entrySet()) {
                node.set(e.getKey(), e.getValue());
            }
        }
        return node;
    }

    /** 从 ONode 反序列化（向后兼容：缺失字段给默认值；不认识的顶层字段进 extras）。 */
    public static LoopTask fromONode(ONode node) {
        String lastResultVal = node.getOrNull("lastResult") != null
                ? node.get("lastResult").getString() : null;
        Instant lastExecutedAtVal = node.getOrNull("lastExecutedAt") != null
                ? Instant.parse(node.get("lastExecutedAt").getString()) : null;
        String cronVal = node.getOrNull("cron") != null
                ? node.get("cron").getString() : null;
        boolean enabledVal = node.getOrNull("enabled") == null || node.get("enabled").getBoolean();
        int currentIterationVal = node.getOrNull("currentIteration") != null
                ? node.get("currentIteration").getInt() : 0;
        boolean runNowVal = node.getOrNull("runNow") != null && node.get("runNow").getBoolean();
        boolean oneShotVal = node.getOrNull("oneShot") != null && node.get("oneShot").getBoolean();
        boolean cancelledVal = node.getOrNull("cancelled") != null && node.get("cancelled").getBoolean();
        boolean autoIntervalVal = node.getOrNull("autoInterval") != null && node.get("autoInterval").getBoolean();

        LoopTask task = new LoopTask(
                node.get("id").getString(),
                node.get("prompt").getString(),
                node.get("intervalMinutes").getInt(),
                cronVal,
                Instant.parse(node.get("createdAt").getString()),
                autoIntervalVal,
                enabledVal,
                runNowVal,
                cancelledVal,
                lastResultVal,
                lastExecutedAtVal,
                currentIterationVal,
                new AtomicBoolean(),
                new AtomicInteger()
        );
        task.oneShot = oneShotVal;

        if (node.isObject()) {
            for (Map.Entry<String, ONode> e : node.getObject().entrySet()) {
                if (!RESERVED_KEYS.contains(e.getKey())) {
                    task.extras.put(e.getKey(), e.getValue());
                }
            }
        }
        // running 是瞬态锁，反序列化后始终为 false
        return task;
    }
}
