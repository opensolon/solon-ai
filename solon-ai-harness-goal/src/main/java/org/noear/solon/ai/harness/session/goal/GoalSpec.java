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
package org.noear.solon.ai.harness.session.goal;

import java.time.Duration;

/**
 * Goal 参数对象 —— 命令行 flag 与编程调用的共同载荷。
 *
 * <p>解决的问题：此前 {@code /goal --max-tokens:2000 --max-duration:30 <objective>} 由
 * {@code GoalCommand} 手工切字符串，编程侧则是
 * {@code attachAndSchedule(session, objective, maxTokens, maxDurationMs, maxIterations)}
 * 五个裸参数（靠 javadoc 才知道第四个是毫秒），两条路径各自拼装、互不同源。</p>
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
 * goal.attachAndSchedule(session, GoalSpec.of("让测试全绿"));
 *
 * // 带预算（单位自描述，可选参数自然缺省）
 * goal.attachAndSchedule(session, GoalSpec.of("修复登录模块的鉴权缺陷")
 *         .maxTokens(2000)
 *         .maxDuration(Duration.ofMinutes(30))
 *         .maxIterations(5));
 * }</pre>
 *
 * @author noear
 * @since 4.2
 */
public final class GoalSpec {
    private final String objective;
    private long maxTokens;      // 0 = 不限制
    private long maxDurationMs;  // 0 = 不限制
    private int maxIterations;   // 0 = 不限制

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
