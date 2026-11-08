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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话 goal 的只读快照（每个会话永远只有一个 goal，按 sessionId 定位，没有任务 id 的概念）。
 *
 * <p>由 {@link GoalExtension#get} 与 {@link GoalListener#onChanged} 交给宿主；状态的一切修改只能经门面
 * （{@code create/update/pause/resume/trigger/remove}），宿主无法绕过状态机。
 * 快照创建后不随运行态变化，需要最新值时重新 {@code get}。</p>
 *
 * <p>状态机见 {@link GoalStatus}。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class GoalSnapshot {

    private final String id;
    private final String objective;
    private final GoalStatus status;
    private final long consumedTokens;
    private final long maxTokens;
    private final int maxIterations;
    private final long maxDurationMs;
    private final int currentIteration;
    private final long startEpochMs;
    private final String lastResult;
    private final long lastExecutedAt;
    private final boolean running;

    private GoalSnapshot(Goal gs, boolean running) {
        this.id = gs.getId();
        this.objective = gs.getObjective();
        this.status = gs.getStatus();
        this.consumedTokens = gs.getConsumedTokens();
        this.maxTokens = gs.getMaxTokens();
        this.maxIterations = gs.getMaxIterations();
        this.maxDurationMs = gs.getMaxDurationMs();
        this.currentIteration = gs.getCurrentIteration();
        this.startEpochMs = gs.getStartEpochMs();
        this.lastResult = gs.getLastResult();
        this.lastExecutedAt = gs.getLastExecutedAt();
        this.running = running;
    }

    /** 由运行态生成快照（包内）。 */
    static GoalSnapshot of(Goal gs, boolean running) {
        return gs == null ? null : new GoalSnapshot(gs, running);
    }

    /** goal 标识（每次创建生成一个新值；仅供展示与区分「是不是同一次创建」，门面不按它定位）。 */
    public String getId() {
        return id;
    }

    /** 目标正文（展示用；创建时若区分了展示正文则为展示正文）。 */
    public String getObjective() {
        return objective;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public long getConsumedTokens() {
        return consumedTokens;
    }

    /** token 预算（0 = 不限制）。 */
    public long getMaxTokens() {
        return maxTokens;
    }

    /** 轮次预算（0 = 不限制）。 */
    public int getMaxIterations() {
        return maxIterations;
    }

    /** 时长预算毫秒（0 = 不限制）。 */
    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    /** 已完成轮次。 */
    public int getCurrentIteration() {
        return currentIteration;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    /** 最近一轮结果摘要（可空）。 */
    public String getLastResult() {
        return lastResult;
    }

    /** 最近一轮执行时间（epoch ms，0 = 未执行）。 */
    public long getLastExecutedAt() {
        return lastExecutedAt;
    }

    /** 快照时刻是否有一轮正在执行。 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 标准展示视图（goal 自己的字段；需要与 loop 任务混排时由宿主追加列表字段）。
     *
     * <p>返回新的可变 Map，宿主可追加自己的展示字段。</p>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "GOAL");
        item.put("prompt", objective);
        item.put("running", running);
        item.put("currentIteration", currentIteration);
        if (maxTokens > 0) item.put("maxTokens", maxTokens);
        if (maxDurationMs > 0) item.put("maxDurationMs", maxDurationMs);
        if (lastResult != null) item.put("lastResult", lastResult);
        if (lastExecutedAt > 0) {
            item.put("lastExecutedAt", Instant.ofEpochMilli(lastExecutedAt).toString());
        }

        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("condition", objective);
        goal.put("status", status.name());
        goal.put("iteration", currentIteration);
        goal.put("maxIterations", maxIterations);
        goal.put("consumedTokens", consumedTokens);
        goal.put("maxTokens", maxTokens);
        if (startEpochMs > 0) {
            goal.put("startedAt", Instant.ofEpochMilli(startEpochMs).toString());
        }
        item.put("goal", goal);
        return item;
    }

    @Override
    public String toString() {
        return "GoalSnapshot{id=" + id + ", status=" + status + ", objective=" + objective + "}";
    }
}
