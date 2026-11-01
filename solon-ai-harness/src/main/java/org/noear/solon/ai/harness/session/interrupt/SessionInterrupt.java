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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.ai.harness.util.TraceUtil;
import reactor.core.Disposable;

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
public final class SessionInterrupt {
    /** 宿主订阅流时写入的运行句柄 key（跨端约定，不得重命名） */
    private static final String ATTR_DISPOSABLE = "disposable";
    /** 中断补写的收尾文本 */
    private static final String CANCEL_TEXT = "用户已取消任务.";

    private SessionInterrupt() {
    }

    /**
     * 中断会话当前正在执行的任务。
     *
     * <p>四步：清队列（待执行与已被 drain 暂存的消息）→ 释放流句柄 → 补取消消息（带 runId 元数据）
     * → 补快照（中断时 agent 收尾被跳过，trace 与取消消息需一并持久化）。</p>
     *
     * @return true = 释放了运行中的流（确有任务被中断）；false = 当前没有正在执行的任务
     */
    public static boolean interrupt(AgentSession session) {
        if (session == null) {
            return false;
        }

        // ① 清排队：无论当前流句柄是否还在，排队项都必须取消（否则停流后又被续发）
        SessionQueue.cancelPending(session);

        // ② 停当前流
        Disposable disposable = (Disposable) session.attrs().remove(ATTR_DISPOSABLE);
        if (disposable == null) {
            return false;
        }
        disposable.dispose();

        // ③ 补取消消息（保留 runId 归属，前端据此挂到正确的轮次气泡）
        ReActTrace trace = TraceUtil.getCurrentTrace(session);
        AssistantMessage assistantMessage = ChatMessage.ofAssistant(CANCEL_TEXT);
        if (trace != null) {
            assistantMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
        }
        session.addMessage(assistantMessage);

        // ④ 补快照：中断时 agent 收尾被跳过，需将当前 trace 与取消消息一并持久化，避免重载后 trace 丢失
        session.updateSnapshot();
        return true;
    }
}
