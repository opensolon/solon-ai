/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

/**
 * 一次插话投递的结果（{@link SteerExtension#steer} 系列方法的返回值）
 *
 * <ul>
 *   <li>{@link #getStatus()}：投递结论，见 {@link SteerStatus}；</li>
 *   <li>{@link #getSteerId()}：本条插话的标识（用于后续撤销，未受理时可能为 null）；</li>
 *   <li>{@link #getQueued()}：投递后本轮邮箱里待注入的条数。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SteerResult {
    private final SteerStatus status;
    private final String steerId;
    private final int queued;

    public SteerResult(SteerStatus status, String steerId, int queued) {
        this.status = status;
        this.steerId = steerId;
        this.queued = queued;
    }

    public SteerStatus getStatus() { return status; }
    public String getStatusCode() { return status.code(); }
    public String getSteerId() { return steerId; }
    public int getQueued() { return queued; }
    public boolean isSteered() { return status == SteerStatus.STEERED; }
    public boolean isBoxFull() { return status == SteerStatus.BOX_FULL; }
    public boolean isNotRunning() {
        return status == SteerStatus.NOT_RUNNING || status == SteerStatus.TURN_CHANGED;
    }
}