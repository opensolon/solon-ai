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
package org.noear.solon.ai.harness.extension.goal;

import java.time.Duration;

/**
 * Goal 参数对象 —— 命令行 flag 与编程调用的共同载荷。
 *
 * <p>{@code /goal} 命令解析出的 flag 与宿主的编程调用都收敛为本对象，再经
 * {@link GoalExtension#create(String, GoalSpec)} 走同一条路径。</p>
 *
 * <p>口径与既有实现一致：
 * <ul>
 *   <li>未设置 = 0 = 不限制（对齐 {@code GoalCommand} 的 DEFAULT_MAX_* 常量）；</li>
 *   <li>{@code --max-duration:30} 的单位是「分钟」，本类统一收敛为 {@link Duration}，
 *       调用点不再出现 {@code 30 * 60_000L} 这类手工换算。</li>
 * </ul>
 *
 * <p><b>用法</b>：</p>
 * <pre>{@code
 * // 最简形态（不设任何预算 = 不限制）
 * goal.create(sessionId, GoalSpec.of("让测试全绿"));
 *
 * // 带预算（单位自描述，可选参数自然缺省）
 * goal.create(sessionId, GoalSpec.of("修复登录模块的鉴权缺陷")
 *         .maxTokens(2000)
 *         .maxDuration(Duration.ofMinutes(30))
 *         .maxIterations(5));
 *
 * // 首轮由宿主自己的流驱动（只挂调度、等下一轮），执行正文含附件、展示正文为用户原句
 * goal.create(sessionId, GoalSpec.of(promptWithAttachments).displayObjective(userText).runNow(false));
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
public final class GoalSpec {
    private final String objective;
    private long maxTokens;      // 0 = 不限制
    private long maxDurationMs;  // 0 = 不限制
    private int maxIterations;   // 0 = 不限制
    private String displayObjective; // null = 与执行正文相同
    private boolean runNow = true;

    private GoalSpec(String objective) {
        this.objective = objective;
    }

    /**
     * 创建参数对象（唯一入口）。
     *
     * @param objective 目标正文（必填；空正文当场失败，对应命令行的 Usage 分支）
     */
    public static GoalSpec of(String objective) {
        if (objective == null || objective.trim().isEmpty()) {
            throw new IllegalArgumentException("goal objective must not be empty");
        }
        return new GoalSpec(objective.trim());
    }

    /** token 预算上限（0 = 不限制）。 */
    public GoalSpec maxTokens(long maxTokens) {
        this.maxTokens = Math.max(0, maxTokens);
        return this;
    }

    /** 时长上限（null = 不限制）。 */
    public GoalSpec maxDuration(Duration maxDuration) {
        this.maxDurationMs = (maxDuration == null ? 0 : Math.max(0, maxDuration.toMillis()));
        return this;
    }

    /** 时长上限（毫秒；命令行分钟数已在此前的解析层换算）。 */
    public GoalSpec maxDurationMs(long maxDurationMs) {
        this.maxDurationMs = Math.max(0, maxDurationMs);
        return this;
    }

    /** 迭代轮次上限（0 = 不限制）。 */
    public GoalSpec maxIterations(int maxIterations) {
        this.maxIterations = Math.max(0, maxIterations);
        return this;
    }

    /**
     * 展示正文（UI 列表/徽章）；null、空白或与执行正文相同时不区分。
     *
     * <p>执行正文可能拼入附件等上下文，展示正文只保留用户原句；两者在创建时一次成型、只广播一次。</p>
     */
    public GoalSpec displayObjective(String displayObjective) {
        this.displayObjective = displayObjective;
        return this;
    }

    /**
     * 是否立即跑首轮（缺省 true）。false = 只挂调度、等下一个调度周期
     * （适用于首轮由宿主自己的流驱动的场景，如桌面端）。
     */
    public GoalSpec runNow(boolean runNow) {
        this.runNow = runNow;
        return this;
    }

    public String getDisplayObjective() {
        return displayObjective;
    }

    public boolean isRunNow() {
        return runNow;
    }

    public String getObjective() {
        return objective;
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    @Override
    public String toString() {
        return "GoalSpec{objective=" + objective + ", maxTokens=" + maxTokens
                + ", maxDurationMs=" + maxDurationMs + ", maxIterations=" + maxIterations + "}";
    }
}
