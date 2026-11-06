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
 * @since 4.2.0
 */
public final class SteerCancelResult {
    /**
     * 撤销结论（与投递侧 {@link SteerResult.Code} 对称；取值与 /web/chat/steer/cancel 应答码同名）。
     */
    public enum Code {
        /** 已从待生效列表移除 */
        CANCELED,
        /** 不在待生效列表（已被注入或已转排队）：终态以 applied / dropped 事件为准，不得宣称撤销成功 */
        NOT_PENDING,
        /** 请求所带 runId 已不是当前轮次：旧 steerId 已无意义 */
        TURN_CHANGED,
        /** steerId 为空或超过长度上限 */
        INVALID_STEER_ID
    }

    private final Code code;
    private final String steerId;
    private final int canceled;
    private final int remaining;

    SteerCancelResult(Code code, String steerId, int canceled, int remaining) {
        this.code = code;
        this.steerId = steerId;
        this.canceled = canceled;
        this.remaining = remaining;
    }

    static SteerCancelResult of(Code code, String steerId, int remaining) {
        return new SteerCancelResult(code, steerId, 0, remaining);
    }

    static SteerCancelResult canceled(String steerId, int canceled, int remaining) {
        return new SteerCancelResult(Code.CANCELED, steerId, canceled, remaining);
    }

    public Code getCode() {
        return code;
    }

    /** 应答码字符串（= {@code getCode().name()}） */
    public String getCodeName() {
        return code.name();
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
        return code == Code.CANCELED;
    }
}
