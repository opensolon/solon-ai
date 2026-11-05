/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

/**
 * 插话撤销结果（不可变）。
 *
 * <p>携带 {@link #getRemaining()}：宿主回填「剩余待生效条数」时不必再调一次 {@code pending().size()}
 * （两次调用之间可能被消费，数值不一致）。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class SteerCancelResult {
    private final SteerCancelStatus status;
    private final String steerId;
    private final int canceled;
    private final int remaining;

    SteerCancelResult(SteerCancelStatus status, String steerId, int canceled, int remaining) {
        this.status = status;
        this.steerId = steerId;
        this.canceled = canceled;
        this.remaining = remaining;
    }

    static SteerCancelResult of(SteerCancelStatus status, String steerId, int remaining) {
        return new SteerCancelResult(status, steerId, 0, remaining);
    }

    static SteerCancelResult canceled(String steerId, int canceled, int remaining) {
        return new SteerCancelResult(SteerCancelStatus.CANCELED, steerId, canceled, remaining);
    }

    public SteerCancelStatus getStatus() {
        return status;
    }

    public String getStatusCode() {
        return status.code();
    }

    /** 单条撤销时为目标 steerId；批量撤销时为 null */
    public String getSteerId() {
        return steerId;
    }

    /** 实际移除条数（已被消费的不计入） */
    public int getCanceled() {
        return canceled;
    }

    /** 撤销后剩余的待生效条数 */
    public int getRemaining() {
        return remaining;
    }

    public boolean isCanceled() {
        return status == SteerCancelStatus.CANCELED;
    }
}
