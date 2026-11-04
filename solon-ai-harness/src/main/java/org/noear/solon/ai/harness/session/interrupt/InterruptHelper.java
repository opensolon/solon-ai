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
package org.noear.solon.ai.harness.session.interrupt;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.session.queue.QueueHelper;
import org.noear.solon.ai.harness.util.TraceUtil;

import java.util.Objects;

/**
 * 会话中断的唯一实现。
 *
 * <p>此前中断逻辑只存在于 {@code /interrupt} 命令里，程序宿主想中断只能拼命令文本，
 * 或自己复刻 {@code attrs().remove("disposable") + dispose()} 这套内部约定。
 * 本类把它收敛为一处，由 {@code HarnessEngine#interrupt(AgentSession)}、
 * {@code /interrupt} 命令、以及 loop 的取消路径共同调用。</p>
 *
 * <p><b>顺序是有意的</b>：先清队列，再停当前流。停流后宿主常在终态回调里续发队首，
 * 若等到那时才清队列，会把刚被「停掉」的任务重新推起来。</p>
 *
 * <p>不依赖 {@code HarnessEngine}：中断只需要会话与运行时句柄，纯单测宿主（engine 为 null）
 * 也要能走通。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class InterruptHelper {
    /** 中断补写的收尾文本 */
    public static final String CANCEL_TEXT = "用户已取消任务.";

    private InterruptHelper() {
    }

    /**
     * 中断会话当前正在执行的任务。
     *
     * <p>三步：清队列（待执行与已被 drain 暂存的消息）→ 停运行流（框架自行登记过的句柄）
     * → 补收尾（取消消息 + 快照）。</p>
     *
     * <p>运行句柄的登记已上收到 {@code ReActRequest}（经 {@link SessionRuns}），
     * 宿主无需再往 {@code session.attrs()} 里写 key。</p>
     *
     * <p><b>返回 {@code false} 不代表没有发生任何事</b>：取消代数依然推进
     * （见 {@link SessionRuns#cancelAll(AgentSession)}）。宿主可能刚把订阅调度到别的线程、
     * 句柄尚未登记，此刻的中断必须能拦住那条「随后才到」的流。
     * 返回 {@code false} 只意味着「调用时没有已登记的活跃流」，不是「用户可以重试」。</p>
     *
     * @return true = 确有运行中的任务被中断；false = 调用时没有已登记的活跃流（但取消代数已推进）
     */
    public static boolean interrupt(AgentSession session) {
        if (session == null) {
            return false;
        }

        // ① 清排队：无论当前流句柄是否还在，排队项都必须取消（否则停流后又被续发）
        QueueHelper.cancelPending(session);

        // ② 停当前运行（新路径优先；旧宿主兼容路径回退）
        boolean interrupted = SessionRuns.cancelAll(session);
        if (interrupted == false) {
            return false;
        }

        // ③ 补收尾：取消消息 + 快照
        //    框架已尽力让 agent 自然收尾，但取消发生在流内部时，宿主的收尾链仍可能被跳过；
        //    这里做幂等式补齐（已有同 runId 的取消消息则不重复写入）。
        writeCancelMessage(session);
        return true;
    }

    /**
     * 只停当前运行流：不清队列，也不补取消消息。
     *
     * <p>对应「这一轮的停止是程序行为，不是用户取消」的场景：轮次由程序接管、随后会重新发起
     * 新一轮（如自动循环的上一轮被新目标顶掉）。此时若照 {@link #interrupt} 的完整语义补写
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
     * 补取消消息与快照（幂等：同一 runId 已有取消消息时不再重复写入）。
     */
    private static void writeCancelMessage(AgentSession session) {
        ReActTrace trace = TraceUtil.getCurrentTrace(session);
        String runId = trace == null ? null : trace.getRunId();

        AssistantMessage assistantMessage = ChatMessage.ofAssistant(CANCEL_TEXT);
        if (runId != null) {
            assistantMessage.addMetadata(AgentTrace.META_RUN_ID, runId);
        }

        // 幂等：避免重复中断又补一条取消消息
        for (ChatMessage message : session.getMessages()) {
            if (CANCEL_TEXT.equals(message.getContent())
                    && Objects.equals(runId, message.getMetadata().get(AgentTrace.META_RUN_ID))) {
                session.updateSnapshot();
                return;
            }
        }

        session.addMessage(assistantMessage);
        session.updateSnapshot();
    }
}
