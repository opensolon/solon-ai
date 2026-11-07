/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

/**
 * 一次插话投递的结果（{@link SteerExtension#steer} 系列方法的返回值）
 *
 * <ul>
 *   <li>{@link #getCode()}：投递结论，见 {@link Code}；</li>
 *   <li>{@link #getSteerId()}：本条插话的标识（用于后续撤销，未受理时可能为 null）；</li>
 *   <li>{@link #getPendingCount()}：投递后本轮邮箱里待注入的条数。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SteerResult {
    /**
     * 投递结论（取值与 /web/chat/steer 应答码同名）。
     */
    public enum Code {
        /** 已进入本轮邮箱，将在下一个推理步注入 */
        STEERED,
        /** 当前没有运行中的轮次 */
        NOT_RUNNING,
        /** 请求所带 runId 已不是当前轮次 */
        TURN_CHANGED,
        /** 本轮邮箱已满 */
        BOX_FULL,
        /** steerId 重复（重试幂等） */
        DUPLICATE_STEER_ID,
        /** 正文为空 */
        EMPTY_TEXT,
        /** 正文超长 */
        TEXT_TOO_LONG,
        /** steerId 超过长度上限 */
        INVALID_STEER_ID,
        /** 插话能力已被关闭（扩展/拦截器 setEnabled(false)），本次未入队 */
        DISABLED,
        /**
         * 多节点部署：本节点不持有该会话（运行中的轮次在持有节点内存里），宿主按 {@code engine.checkOwner} 的持有者转发
         *
         * @since 4.2.0
         */
        NOT_OWNER
    }

    private final Code code;
    private final String steerId;
    private final int pendingCount;

    SteerResult(Code code, String steerId, int pendingCount) {
        this.code = code;
        this.steerId = steerId;
        this.pendingCount = pendingCount;
    }

    public Code getCode() { return code; }
    /** 应答码字符串（= {@code getCode().name()}） */
    public String getCodeName() { return code.name(); }
    public String getSteerId() { return steerId; }
    /** 投递后本轮邮箱里待注入的条数 */
    public int getPendingCount() { return pendingCount; }
    public boolean isSteered() { return code == Code.STEERED; }
    public boolean isBoxFull() { return code == Code.BOX_FULL; }
    /** 当前没有运行中的轮次 */
    public boolean isNotRunning() { return code == Code.NOT_RUNNING; }
    /** 轮次已切换（请求所带 runId 过期） */
    public boolean isTurnChanged() { return code == Code.TURN_CHANGED; }

    @Override
    public String toString() {
        return "SteerResult{code=" + code + ", steerId=" + steerId + ", pendingCount=" + pendingCount + "}";
    }
}
