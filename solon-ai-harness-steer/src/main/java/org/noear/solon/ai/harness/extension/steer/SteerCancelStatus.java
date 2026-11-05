/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

/**
 * 插话撤销结果（与投递侧 {@link SteerStatus} 对称；取值与 /web/chat/steer/cancel 应答码同名）。
 *
 * @author noear
 * @since 4.2.1
 */
public enum SteerCancelStatus {
    /** 已从待生效列表移除 */
    CANCELED("CANCELED"),
    /** 不在待生效列表（已被注入或已转排队）：终态以 applied / dropped 事件为准，不得宣称撤销成功 */
    NOT_PENDING("NOT_PENDING"),
    /** 请求所带 runId 已不是当前轮次：旧 steerId 已无意义 */
    TURN_CHANGED("TURN_CHANGED"),
    /** steerId 为空或超过长度上限 */
    INVALID_STEER_ID("INVALID_STEER_ID");

    private final String code;

    SteerCancelStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
