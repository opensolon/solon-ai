/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.lang.Preview;

/**
 * 一轮运行的用量（计费、限流、看板用）。
 *
 * <p>在轮次结束时（被中断时在停流之前）取快照：token 取自运行轨迹的指标，工具调用数只数成功的调用，
 * 耗时从开流计起（未开流为 0）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class RunUsage {
    static final RunUsage EMPTY = new RunUsage(0L, 0L, 0L, 0, 0L);

    private final long totalTokens;
    private final long promptTokens;
    private final long completionTokens;
    private final int toolCalls;
    private final long elapsedMs;

    RunUsage(long totalTokens, long promptTokens, long completionTokens, int toolCalls, long elapsedMs) {
        this.totalTokens = totalTokens;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.toolCalls = toolCalls;
        this.elapsedMs = elapsedMs;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    /** 成功的工具调用数（含内部工具） */
    public int getToolCalls() {
        return toolCalls;
    }

    /** 开流到结束的耗时（毫秒；未开流为 0） */
    public long getElapsedMs() {
        return elapsedMs;
    }

    @Override
    public String toString() {
        return "RunUsage{tokens=" + totalTokens + ", tools=" + toolCalls + ", elapsedMs=" + elapsedMs + "}";
    }
}
