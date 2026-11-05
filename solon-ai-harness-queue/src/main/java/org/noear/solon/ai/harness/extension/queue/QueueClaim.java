/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;

/**
 * 一次队列领取（领取事务）。
 *
 * <p>把「领取」提升为一等对象，把三件宿主原本必须自己保管的东西收进 harness：</p>
 * <ol>
 *   <li><b>领取到的任务</b>：{@link #getItem()}，无需再往会话 attrs 里塞一份再强转取回；</li>
 *   <li><b>领取时刻的代际快照</b>：{@link #getGeneration()}，宿主不再需要在终态现取
 *       {@code generation(session)}——那会让代际防线恒真（详见下文）；</li>
 *   <li><b>终态收口</b>：{@link #acknowledge()} 成功 / {@link #rollback()} 回滚，
 *       身份与代际校验由 harness 内部完成，幂等。</li>
 * </ol>
 *
 * <p><b>为什么必须有代际快照</b>：{@code cancelPending}（清空待执行）会递增代际并移除
 * in-flight。若宿主在终态「现取」代际，取到的必然是新代际，{@code isGenerationActive}
 * 恒真，于是被用户 Stop 掉的任务会重新入队继续执行——丢弃语义被破坏。本类在领取瞬间
 * 绑定代际，{@code rollback()} 之后对已清空会话必然返回 {@code false}。</p>
 *
 * <p><b>收口是强制契约</b>：领取后必须调 {@link #acknowledge()} 或 {@link #rollback()} 之一，
 * 且应尽早（同步任务在回调返回前、异步任务在流终态）。handler 抛异常时内核自动回滚，
 * 但「handler 正常返回却没收口」属于宿主 bug——内核无法区分这是异步持有还是忘记收口，
 * 因此不再提供 {@code close()} 这类“好像会兜底”的逃逸口（实测零宿主调用）。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class QueueClaim {
    private enum State {
        /** 已领取、尚未收口 */
        OPEN,
        /** 成功终态 */
        ACKNOWLEDGED,
        /** 回滚（启动失败 / 未收口即释放） */
        ROLLED_BACK
    }

    private final AgentSession session;
    private final QueueItem item;
    private final long generation;
    private volatile State state = State.OPEN;

    QueueClaim(AgentSession session, QueueItem item, long generation) {
        this.session = session;
        this.item = item;
        this.generation = generation;
    }

    /** 本次领取的任务（可能为 null 仅当内核内部构造失败，正常领取必定非 null）。 */
    @Nullable
    public QueueItem getItem() {
        return item;
    }

    /** 领取时刻的代际快照（仅供内部代际校验与诊断，宿主无需使用）。 */
    long getGeneration() {
        return generation;
    }

    /** 代际是否仍然有效（= 领取之后没有发生过 {@code cancelPending}）。 */
    public boolean isValid() {
        return QueueHelper.isGenerationActive(session, generation);
    }

    /** 是否已收口（成功或回滚）。 */
    public boolean isSettled() {
        return state != State.OPEN;
    }

    /**
     * 成功终态：从 in-flight 移除并落盘。
     *
     * @return true = 本次调用完成了收口；false = 已收口过 / 身份不匹配（in-flight 已被他人替换）
     */
    public boolean acknowledge() {
        if (state != State.OPEN || item == null) {
            return false;
        }
        if (!QueueHelper.acknowledge(session, item.getId())) {
            return false;
        }
        state = State.ACKNOWLEDGED;
        // 注销跟着状态迁移走：宿主绕过 QueueDispatch.settle 直接 acknowledge 时也不能留下登记
        QueueHelper.unregisterDispatchOf(session, this);
        return true;
    }

    /**
     * 回滚：把任务放回队首（用领取时刻的代际判定是否还该回写）。
     *
     * @return true = 已放回队首；false = 已收口过 / 代际已变（期间被 cancelPending 清空，应丢弃）
     */
    public boolean rollback() {
        if (state != State.OPEN || item == null) {
            return false;
        }
        if (QueueHelper.requeueFront(session, item, generation)) {
            state = State.ROLLED_BACK;
            QueueHelper.unregisterDispatchOf(session, this);
            return true;
        }
        // 回写被拒只有两种原因，处理方式相反：
        //   1) 代际已变（期间被 cancelPending 清空）——任务应当被丢弃，清掉 in-flight 残留；
        //   2) 代际未变但队列已满——任务绝不能丢，保持 in-flight 不给其他任务插队。
        if (!isValid()) {
            QueueHelper.acknowledge(session, item.getId());
            state = State.ROLLED_BACK;
            QueueHelper.unregisterDispatchOf(session, this);
        }
        // 回写被拒且代际仍有效（队列满）：任务保持 in-flight，派发登记也保持——
        // 它不是「已收口」，后续仍要有人对它收口，这里注销会让任务永久无主。
        return false;
    }

    @Override
    public String toString() {
        return "QueueClaim{item=" + (item == null ? null : item.getId())
                + ", generation=" + generation + ", state=" + state + "}";
    }
}
