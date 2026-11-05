/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

/**
 * steer 插件的会话属性<b>契约键</b>登记（{@code session.steer} 命名空间归本插件）。
 *
 * <p>与 queue 的 {@code QueueAttrs}、goal 的 {@code GoalAttrs} 同一规约：插件自有键集中登记在插件模块内，
 * 核心 {@code HarnessAttrs} 不认识它们。</p>
 *
 * <p><b>宿主不得读写</b>：插话状态（轮次 + 邮箱 + 暂存）一律经 {@link SteerExtension} 门面查询与撤销，
 * 计数类事实经 {@code engine.status(session).get(SteerExtension.STATUS_PENDING)} 读取。</p>
 *
 * <p>本插件的键都在 {@code attrs()} 里（transient，不落快照），重命名不会丢失持久化数据
 * （原 {@code harness.steer} / {@code harness.steerBox} / {@code harness.activeRunId} 已废弃不再读写）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public interface SteerAttrs {
    /**
     * 会话级插话状态（值为包内 {@code SteerState}：当前轮次 + 本轮邮箱 + 转排队失败暂存，transient）
     */
    String STEER = "session.steer";
}
