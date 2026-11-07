/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

/**
 * 插话邮箱的容量与长度约束（不可变值对象）。
 *
 * <p>此前三个上限是 {@link SteerMailbox} 上的 {@code public static final} 常量，
 * 宿主想给长会话 / IM 场景放宽只能改库。改为值对象后由
 * {@link SteerExtension#builder()} 注入，内核逻辑只读实例字段。</p>
 *
 * <p>宿主的入参校验<b>不需要</b>再读这些值：投递内核会以同一份约束返回
 * {@link SteerExtension#REASON_TEXT_TOO_LONG} / {@link SteerExtension#REASON_INVALID_STEER_ID} /
 * {@link SteerExtension#REASON_BOX_FULL}，宿主只需映射应答码。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SteerLimits {
    /** 缺省约束：邮箱 5 条、单条 4096 字符、ID 128 字符。 */
    public static final SteerLimits DEFAULT = new SteerLimits(
            SteerMailbox.MAX_BOX_SIZE, SteerMailbox.MAX_TEXT_LENGTH, SteerMailbox.MAX_ID_LENGTH);

    private final int maxBoxSize;
    private final int maxTextLength;
    private final int maxIdLength;

    public SteerLimits(int maxBoxSize, int maxTextLength, int maxIdLength) {
        if (maxBoxSize <= 0 || maxTextLength <= 0 || maxIdLength <= 0) {
            throw new IllegalArgumentException("steer limits must be positive");
        }
        this.maxBoxSize = maxBoxSize;
        this.maxTextLength = maxTextLength;
        this.maxIdLength = maxIdLength;
    }

    /** 邮箱容量上限（条） */
    public int getMaxBoxSize() {
        return maxBoxSize;
    }

    /** 单条插话文本长度上限（字符） */
    public int getMaxTextLength() {
        return maxTextLength;
    }

    /** steerId 长度上限（字符） */
    public int getMaxIdLength() {
        return maxIdLength;
    }
}
