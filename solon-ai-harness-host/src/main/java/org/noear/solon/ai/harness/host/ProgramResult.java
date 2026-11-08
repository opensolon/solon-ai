/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.ai.harness.extension.loop.LoopOutcome;
import org.noear.solon.lang.Nullable;

/**
 * 一轮后台任务（goal / loop）的归类（包内）：出错 → 抛给插件（参与错误分级熔断）；被中断、没有最终回答 → 跳过；否则完成。
 *
 * <p>从 {@link HarnessHost} 拆出：编排主线只管「跑一轮」，怎么把终态翻译成插件的结局在这里。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class ProgramResult {
    private final RunOutcome outcome;
    private final boolean hasEvidence;
    private final String skipReason;
    private final Throwable failure;

    private ProgramResult(RunOutcome outcome, boolean hasEvidence, String skipReason, Throwable failure) {
        this.outcome = outcome;
        this.hasEvidence = hasEvidence;
        this.skipReason = skipReason;
        this.failure = failure;
    }

    /**
     * @param hasEvidence 本轮是否调过「证据」工具（{@link GoalOutcome#isEvidenceTool}）
     */
    static ProgramResult of(RunOutcome o, boolean hasEvidence) {
        if (o.isFailed()) {
            return failed(o.getError());
        }
        if (o.isCancelled()) {
            return skip("cancelled");
        }
        if (o.getResultText() == null || o.getResultText().trim().isEmpty()) {
            return skip("no final answer");
        }
        return new ProgramResult(o, hasEvidence, null, null);
    }

    static ProgramResult skip(String reason) {
        return new ProgramResult(null, false, reason, null);
    }

    static ProgramResult failed(@Nullable Throwable error) {
        return new ProgramResult(null, false, null,
                error != null ? error : new IllegalStateException("run failed"));
    }

    GoalOutcome toGoalOutcome() throws Exception {
        if (skipReason != null) {
            return GoalOutcome.skip(skipReason);
        }
        rethrowFailure();
        return GoalOutcome.done(outcome.getResultText(), hasEvidence, outcome.getUsage().getTotalTokens());
    }

    LoopOutcome toLoopOutcome() throws Exception {
        if (skipReason != null) {
            return LoopOutcome.skip(skipReason);
        }
        rethrowFailure();
        return LoopOutcome.done(outcome.getResultText());
    }

    private void rethrowFailure() throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException(failure.getMessage() != null ? failure.getMessage() : failure.toString(), failure);
    }
}
