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

import org.noear.solon.ai.harness.turn.InterruptPlan;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.harness.util.TraceUtil;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HarnessInterrupts} 核心契约（停流 + 补收尾）。
 *
 * <p>锁定三点：
 * <ol>
 *   <li>停流恰一次 + 补取消消息 + 补 runId 元数据；</li>
 *   <li>无运行任务时返回 false，且不写消息（避免空会话被污染）；</li>
 *   <li>null 会话安全。</li>
 * </ol>
 *
 * <p>「先清积压再停流」已上提为引擎的扩展钩子（{@code HarnessExtension#onInterrupt}），
 * 顺序契约见 {@code HarnessPluginContractTest}，队列语义见 queue 插件的 {@code QueueInterruptTest}。</p>
 */
class SessionInterruptTest {

    /** 核心一步式中断（= 认领 + 提交，不含插件参与；引擎入口另有 onInterrupt 回调） */
    private static boolean interrupt(AgentSession session) {
        org.noear.solon.ai.harness.turn.InterruptPlan plan = HarnessInterrupts.claim(session);
        return plan != null && plan.commit();
    }


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
    void interruptsFrameworkRegisteredRunWithoutHostAttrs() {
        // 新路径：宿主（CLI/Web/桌面）完全不写 attrs，句柄由 ReActRequest 自行登记
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, countingDisposable(disposed));

        assertTrue(interrupt(session));
        assertEquals(1, disposed.get(), "框架登记的句柄必须被释放");
        assertFalse(SessionRuns.isBusy(session));
        assertEquals(1, session.getMessages().size());
    }

    @Test
    void nullSessionIsSafeAndReturnsFalse() {
        assertFalse(interrupt(null));
    }

    @Test
    void noRunningTaskReturnsFalseAndWritesNothing() {
        AgentSession session = InMemoryAgentSession.of();

        assertFalse(interrupt(session));
        assertTrue(session.getMessages().isEmpty(), "无任务中断不得写取消消息");
    }

    @Test
    void disposesExactlyOnceAndAppendsCancelMessage() {
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, countingDisposable(disposed));

        assertTrue(interrupt(session));
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
        session.getContext().put(TraceUtil.TRACE_KEY_MAIN, trace);
        SessionRuns.attach(session, countingDisposable(new AtomicInteger()));

        interrupt(session);

        AssistantMessage last = (AssistantMessage) session.getMessages().get(session.getMessages().size() - 1);
        assertNotNull(last.getMetadataAs(AgentTrace.META_RUN_ID));
        assertEquals(last.getMetadataAs(AgentTrace.META_RUN_ID), trace.getRunId());
    }

    @Test
    void repeatedInterruptDoesNotDuplicateMessage() {
        AgentSession session = InMemoryAgentSession.of();
        SessionRuns.attach(session, countingDisposable(new AtomicInteger()));

        assertTrue(interrupt(session));
        // 第二次：句柄已被移除 → 无任务分支，不得再写消息
        assertFalse(interrupt(session));
        assertEquals(1, session.getMessages().size());
    }
}
