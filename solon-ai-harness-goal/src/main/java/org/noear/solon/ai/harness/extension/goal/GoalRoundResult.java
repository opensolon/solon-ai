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

import lombok.Getter;

/**
 * 单轮执行结果（goal 完成判定使用的客观事实）。
 *
 * <p>不含任何业务判定语义，仅承载一次执行的客观事实：是否有结果、是否有工具调用证据、
 * 消耗的 token、最终文本。完成与否的解释权在使用方（{@code GoalEngine.executeRound}）。</p>
 *
 * <p>注意：{@code loop} 执行通道不使用本类型（{@code LoopExecutor} 返回自己的三态结论
 * {@code LoopOutcome}）——两个执行域各自定义通道，不共享返回类型。</p>
 *
 * @author noear
 * @since 3.9.4
 */
@Getter
public class GoalRoundResult {
    /** 本轮是否产生了结果（finalResult 非 null），非「目标已完成」语义。 */
    private final boolean completed;
    private final boolean hasToolCalls;
    private final long tokensUsed;
    private final String finalResult;

    private GoalRoundResult(boolean completed, boolean hasToolCalls, long tokensUsed, String finalResult) {
        this.completed = completed;
        this.hasToolCalls = hasToolCalls;
        this.tokensUsed = tokensUsed;
        this.finalResult = finalResult;
    }

    /**
     * 从执行结果文本构建（无真实 token / 工具证据时的启发式降级）。
     *
     * <p><b>不得用于完成判定路径</b>：这里的 {@code hasToolCalls} 是文本长度启发式
     * （长于 20 字且不以 {@code error:} 开头），与「是否真的执行了工具」无关。
     * 历史事故：正因为没有真实证据时退化成这个启发式，CLI/Web 上的「完成需行动证据」
     * 校验变成「回答够长就算做了事」。真实执行路径必须用 {@link #of(boolean, long, String)}。</p>
     *
     * <p>现存用途仅两处：缺省执行器的异常分支（文本恒以 {@code error:} 开头，启发式恒为 false）
     * 与单元测试。新增生产调用前请先确认你确实拿不到真实 trace。</p>
     */
    public static GoalRoundResult ofText(String text) {
        boolean hasToolCalls = text != null && text.length() > 20
                && !text.startsWith("error:") && !text.equals("ok");
        long tokens = text != null ? Math.max(1, text.length() / 4) : 0;
        return new GoalRoundResult(text != null, hasToolCalls, tokens, text);
    }

    /** 使用真实工具调用与 token 信息构建。 */
    public static GoalRoundResult of(boolean hasToolCalls, long tokensUsed, String text) {
        return new GoalRoundResult(text != null, hasToolCalls, tokensUsed, text);
    }
}
