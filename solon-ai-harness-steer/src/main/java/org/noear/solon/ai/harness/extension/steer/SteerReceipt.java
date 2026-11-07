/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.lang.Nullable;

/**
 * 插话投递 / 撤销成功时的回执（{@code HarnessResult<SteerReceipt>} 在 OK 时的值，不可变）。
 *
 * <ul>
 *   <li>{@link #getSteerId()}：本条插话的标识（投递时用于后续撤销；批量撤销时为 null）；</li>
 *   <li>{@link #getCanceled()}：实际撤销的条数（投递为 0；已被消费的不计入）；</li>
 *   <li>{@link #getPending()}：操作后仍待生效的条数（本轮邮箱 + 转排队失败暂存）。</li>
 * </ul>
 *
 * <p>失败结局（邮箱满、轮次已切换等）读 {@code HarnessResult} 的粗码与细分码，见 {@link SteerExtension} 的 {@code REASON_*}。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SteerReceipt {
    private final String steerId;
    private final int canceled;
    private final int pending;

    SteerReceipt(@Nullable String steerId, int canceled, int pending) {
        this.steerId = steerId;
        this.canceled = canceled;
        this.pending = pending;
    }

    /** 插话标识（投递与单条撤销时为目标 steerId；批量撤销时为 null） */
    @Nullable
    public String getSteerId() {
        return steerId;
    }

    /** 实际撤销条数（投递为 0） */
    public int getCanceled() {
        return canceled;
    }

    /** 操作后仍待生效的条数 */
    public int getPending() {
        return pending;
    }

    @Override
    public String toString() {
        return "SteerReceipt{steerId=" + steerId + ", canceled=" + canceled + ", pending=" + pending + "}";
    }
}
