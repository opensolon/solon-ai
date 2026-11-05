/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.agent.session.SessionRuns.Claim;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.turn.InterruptPlan;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.lang.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话中断的核心实现（停流 + 补收尾）。
 *
 * <p>此前中断逻辑只存在于 {@code /interrupt} 命令里，程序宿主想中断只能拼命令文本，
 * 或自己复刻 {@code attrs().remove("disposable") + dispose()} 这套内部约定。
 * 本类把它收敛为一处，由 {@code HarnessEngine#interrupt(AgentSession)} 调用。</p>
 *
 * <p><b>只管核心语义</b>：本类不认识任何插件。用户级中断的插件参与（如 queue 清积压）由引擎在调用本类
 * <b>之前</b>回调 {@code HarnessExtension#onInterrupt}，以此维持「先清积压再停流」的顺序。
 * 因此宿主<b>必须</b>经引擎中断（{@code engine.interrupt} / {@code engine.prepareInterrupt}）；
 * 本类的 {@link #claim} 只是引擎内部的核心认领步骤，只停流、不会清积压。</p>
 *
 * <p>包内可见（原 {@code session.interrupt.InterruptHelper}）：对外只有 {@link InterruptPlan} 与引擎的
 * {@code interrupt / prepareInterrupt / interruptStream / interruptStreamActive}。</p>
 *
 * @author noear
 * @since 4.2
 */
final class HarnessInterrupts {
    private HarnessInterrupts() {
    }

    /**
     * 中断的核心认领步骤：原子认领运行句柄（不停流、不写取消消息；<b>不含插件参与</b>）。
     *
     * <p>由 {@code HarnessEngine#prepareInterrupt} 在回调完扩展的 {@code onInterrupt} 之后调用。
     * 宿主不要直接调用：绕过引擎就绕过了插件参与（如清积压）。</p>
     *
     * <p>「判定忙碌」与「摘下句柄」由 {@link SessionRuns#claim(AgentSession)} 在同一临界区完成：
     * 两个并发的停止只会有一者拿到 {@code hasActiveRun()==true} 的 plan，不会重复写取消消息、
     * 重复领终态。返回的 plan 无活跃流时取消代数也已推进（能拦住「随后才到」的流）。</p>
     *
     * @return 中断计划；null 仅当会话为 null
     * @since 4.2.0
     */
    @Nullable
    public static InterruptPlan claim(@Nullable AgentSession session) {
        if (session == null) {
            return null;
        }

        // 原子认领：摘下句柄与推进取消代数是同一临界区，并发停止只一者成功
        Claim claim = SessionRuns.claim(session);

        return new InterruptPlanImpl(claim, session);
    }

    /**
     * 只停当前运行流：不清队列，也不补取消消息。
     *
     * <p>对应「这一轮的停止是程序行为，不是用户取消」的场景：轮次由程序接管、随后会重新发起
     * 新一轮（如自动循环的上一轮被新目标顶掉）。此时若照 {@code engine.interrupt} 的完整语义补写
     * 取消消息，会话历史里就会出现「用户已取消」的假象，与真实的用户取消混为一谈；
     * 若顺带清队列，宿主刚排好的下一批任务也会被一并抹掉。</p>
     *
     * <p><b>返回 {@code false} 不代表什么都没发生</b>：取消代数依然推进，取消掉的是一条
     * 「已创建、尚未订阅」的流也能被随后拦住（同 {@link org.noear.solon.ai.agent.session.SessionRuns#cancelAll}）。
     * 返回 {@code false} 只意味着「调用时没有已登记的活跃流」。</p>
     *
     * @return true = 确有运行中的流被停掉；false = 调用时没有已登记的活跃流（但取消代数已推进）
     * @since 4.2.0
     */
    public static boolean interruptStreamOnly(AgentSession session) {
        if (session == null) {
            return false;
        }

        return SessionRuns.cancelAll(session);
    }

    /**
     * 只停当前运行流，且<b>不推进取消代数</b>。
     *
     * <p>与 {@link #interruptStreamOnly} 的差别在于「这次停流算不算取消」：
     * 推进代数后，被顶掉的旧流能据此判定「自己是被取消的」，从而不再发终态帧（适合 Stop）；
     * 不推进代数则用于「新轮次顶掉旧轮次」——旧流本身就该自然结束，只是不再被需要，
     * 若把它当成被取消，会把新轮次的收尾也牵连进去。</p>
     *
     * @return true = 确有活跃流被停掉
     * @since 4.2.0
     */
    public static boolean interruptStreamActive(AgentSession session) {
        if (session == null) {
            return false;
        }

        return SessionRuns.cancelActive(session);
    }

    /**
     * 补取消消息与快照（幂等：同一 runId 已有取消消息时不再重复写入）。
     *
     * <p>供需要自己控制事件时序的宿主（如 Web 端要求“先落库、后回执”）单独调用；
     * 不中断流，也不清队列。</p>
     */
    public static void writeCancel(AgentSession session) {
        if (session == null) {
            return;
        }
        writeCancelMessage(session);
    }

    /**
     * {@link InterruptPlan} 的唯一实现：认领凭证闭合后补收尾与停流，全部幂等。
     */
    private static final class InterruptPlanImpl implements InterruptPlan {
        private final AgentSession session;
        private final @Nullable Claim claim;
        private final AtomicBoolean done = new AtomicBoolean();
        /** 取消消息是否已落库（窗口内提前写与 commit 内部写共用一个开关，保证只落盘一次） */
        private final AtomicBoolean cancelWritten = new AtomicBoolean();

        private InterruptPlanImpl(@Nullable Claim claim, AgentSession session) {
            this.claim = claim;
            this.session = session;
        }

        @Override
        public boolean hasActiveRun() {
            return claim != null;
        }

        @Override
        public boolean writeCancelEarly() {
            if (claim == null || done.get()) {
                return false;
            }
            if (cancelWritten.compareAndSet(false, true) == false) {
                return false;
            }
            writeCancelMessage(session);
            return true;
        }

        @Override
        public boolean commit() {
            if (done.compareAndSet(false, true) == false) {
                return false;
            }

            boolean hadActiveRun = claim != null;
            if (hadActiveRun) {
                // 补收尾（若窗口内已提前写则跳过，保证一次中断只落盘一次），再停流。
                if (cancelWritten.compareAndSet(false, true)) {
                    writeCancelMessage(session);
                }
                claim.dispose();
            }
            return hadActiveRun;
        }

        @Override
        public void close() {
            // 兜底与提交等价：close 幂等且不抛异常，已提交过则是空操作
            commit();
        }
    }

    /**
     * 补取消消息与快照（幂等：同一 runId 已有取消消息时不再重复写入）。
     */
    private static void writeCancelMessage(AgentSession session) {
        ReActTrace trace = TraceUtil.getCurrentTrace(session);
        String runId = trace == null ? null : trace.getRunId();

        AssistantMessage assistantMessage = ChatMessage.ofAssistant(InterruptPlan.CANCEL_TEXT);
        if (runId != null) {
            assistantMessage.addMetadata(AgentTrace.META_RUN_ID, runId);
        }

        // 幂等：避免重复中断又补一条取消消息
        for (ChatMessage message : session.getMessages()) {
            if (InterruptPlan.CANCEL_TEXT.equals(message.getContent())
                    && Objects.equals(runId, message.getMetadata().get(AgentTrace.META_RUN_ID))) {
                session.updateSnapshot();
                return;
            }
        }

        session.addMessage(assistantMessage);
        session.updateSnapshot();
    }
}
