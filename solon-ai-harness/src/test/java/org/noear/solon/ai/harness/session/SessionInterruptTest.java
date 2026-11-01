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
package org.noear.solon.ai.harness.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.interrupt.SessionInterrupt;
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.ai.harness.util.TraceKeys;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionInterrupt} 契约（{@code engine.interrupt(session)} 与 {@code /interrupt} 的唯一实现）。
 *
 * <p>锁定四点：
 * <ol>
 *   <li>停流恰一次 + 补取消消息 + 补 runId 元数据；</li>
 *   <li>无运行任务时返回 false，且不写消息（避免空会话被污染）；</li>
 *   <li><b>先清队列再停流</b>：排队项必须一并取消，否则停流后又被续发；</li>
 *   <li>null 会话安全。</li>
 * </ol>
 */
class SessionInterruptTest {

    private static Disposable countingDisposable(AtomicInteger counter) {
        return new Disposable() {
            @Override
            public void dispose() {
                counter.incrementAndGet();
            }

            @Override
            public boolean isDisposed() {
                return counter.get() > 0;
            }
        };
    }

    @Test
    void nullSessionIsSafeAndReturnsFalse() {
        assertFalse(SessionInterrupt.interrupt(null));
    }

    @Test
    void noRunningTaskReturnsFalseAndWritesNothing() {
        AgentSession session = InMemoryAgentSession.of();

        assertFalse(SessionInterrupt.interrupt(session));
        assertTrue(session.getMessages().isEmpty(), "无任务中断不得写取消消息");
    }

    @Test
    void disposesExactlyOnceAndAppendsCancelMessage() {
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        session.attrs().put("disposable", countingDisposable(disposed));

        assertTrue(SessionInterrupt.interrupt(session));
        assertEquals(1, disposed.get());
        assertEquals(1, session.getMessages().size());
        assertTrue(session.getMessages().get(0) instanceof AssistantMessage);
        assertEquals("用户已取消任务.", session.getMessages().get(0).getContent());
    }

    @Test
    void cancelMessageCarriesRunIdWhenTraceExists() {
        AgentSession session = InMemoryAgentSession.of();
        // runId 惰性生成：trace 只需放入 __main 即可被 TraceUtil 取到
        ReActTrace trace = new ReActTrace();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, trace);
        session.attrs().put("disposable", countingDisposable(new AtomicInteger()));

        SessionInterrupt.interrupt(session);

        AssistantMessage last = (AssistantMessage) session.getMessages().get(session.getMessages().size() - 1);
        assertNotNull(last.getMetadataAs(AgentTrace.META_RUN_ID));
        assertEquals(last.getMetadataAs(AgentTrace.META_RUN_ID), trace.getRunId());
    }

    @Test
    void repeatedInterruptDoesNotDuplicateMessage() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put("disposable", countingDisposable(new AtomicInteger()));

        assertTrue(SessionInterrupt.interrupt(session));
        // 第二次：句柄已被移除 → 无任务分支，不得再写消息
        assertFalse(SessionInterrupt.interrupt(session));
        assertEquals(1, session.getMessages().size());
    }

    @Test
    void cancelsPendingQueueItemsSoTheyAreNotRedispatched() {
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.enqueue(session, "排队一", InputOrigin.channel("test"));
        SessionQueue.enqueue(session, "排队二", InputOrigin.channel("test"));
        session.attrs().put("disposable", countingDisposable(new AtomicInteger()));

        assertTrue(SessionQueue.hasItems(session));
        assertTrue(SessionInterrupt.interrupt(session));
        assertFalse(SessionQueue.hasItems(session), "中断必须连带取消排队项，否则停流后又被续发");
        assertEquals(0, SessionQueue.pendingSize(session));
    }

    @Test
    void cancelsQueueEvenWhenNoStreamIsRunning() {
        // 关键顺序契约：先清队列，再判断流句柄。没有流但有排队项时也必须清干净。
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.enqueue(session, "排队一", InputOrigin.channel("test"));

        assertFalse(SessionInterrupt.interrupt(session), "无运行流时返回 false");
        assertEquals(0, SessionQueue.pendingSize(session), "排队项仍必须被取消");
    }
}
