/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.lang.Preview;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本次请求开出的那一轮（{@link SubmitResult#getRun()}）：按请求拿结果，不必写 sink、也不必轮询。
 *
 * <pre>{@code
 * RunOutcome o = host.submit(tenant, sid, SubmitSpec.of("hello")).getRun().await(Duration.ofMinutes(5));
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class RunHandle {
    private final HostRun run;
    private final CompletableFuture<RunOutcome> future = new CompletableFuture<>();
    private final Runnable canceller;

    RunHandle(HostRun run, Runnable canceller) {
        this.run = run;
        this.canceller = canceller;
    }

    public HostRun getRun() {
        return run;
    }

    /** 终态（每轮恰好完成一次，不会异常完成：失败也是一个 {@link RunOutcome}） */
    public CompletableFuture<RunOutcome> future() {
        return future;
    }

    public boolean isDone() {
        return future.isDone();
    }

    /**
     * 等待终态。
     *
     * @throws TimeoutException 超时（本轮仍在运行，需要时调 {@link #cancel()}）
     */
    public RunOutcome await(Duration timeout) throws TimeoutException, InterruptedException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * 停掉这一轮（只停当前流、保留排队，等价于 {@code InterruptMode.CANCEL_STREAM}）；已结束时什么都不做。
     */
    public void cancel() {
        if (future.isDone() == false) {
            canceller.run();
        }
    }

    void complete(RunOutcome outcome) {
        future.complete(outcome);
    }
}
