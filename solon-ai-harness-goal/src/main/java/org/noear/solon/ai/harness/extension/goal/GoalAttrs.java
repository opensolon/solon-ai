/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.goal;

/**
 * goal 插件的会话属性<b>契约键</b>登记（{@code session.goal.*} 命名空间归本插件）。
 *
 * <p>与 queue 的 {@code QueueAttrs}、steer 的 {@code SteerAttrs} 同一规约：插件自有键集中登记在插件模块内，
 * 核心 {@code HarnessAttrs} 不认识它们。</p>
 *
 * <p><b>宿主不得直接读写</b>：投影读写走 {@link GoalProjection}，运行态走 {@link GoalExtension} 门面，
 * 状态事实经 {@code engine.status(session).get(GoalExtension.STATUS_GOAL)} 读取。</p>
 *
 * <p><b>键值不可变</b>：{@link #GOAL} 会写进 FlowContext 持久化，改值会丢失存量 goal。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public interface GoalAttrs {
    /**
     * goal 快照投影（{@code FlowContext.data()} 的持久化 key，值为 Map；单槽：一条 goal 记录）
     */
    String GOAL = "session.goal";

    /**
     * goal 运行态实例（{@code attrs()} 中的 {@link GoalState}，transient；首次访问时从投影 hydrate）
     */
    String GOAL_STATE = "session.goal.state";

    /**
     * 轮次防重入标记（{@code attrs()}，一轮执行期间为 TRUE；清除时 notifyAll 唤醒停等者）
     */
    String GOAL_RUNNING = "session.goal.running";
}