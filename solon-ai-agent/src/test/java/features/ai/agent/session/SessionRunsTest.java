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
package features.ai.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionRuns} 契约：会话运行句柄的唯一登记处。
 *
 * <p>锁定四点：
 * <ol>
 *   <li>登记表不暴露 attrs key，宿主无从「写错」；</li>
 *   <li>注销按值进行：先结束的流不得摘掉仍在跑的流；</li>
 *   <li>取消代数覆盖「已创建、未订阅」的窗口；</li>
 *   <li>取消只作用于本会话。</li>
 * </ol>
 */
class SessionRunsTest {

    private static Disposable handle(AtomicInteger counter) {
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
    @DisplayName("无运行流时：isBusy 为 false，cancelAll 不推进语义")
    void idleSessionIsNotBusy() {
        AgentSession session = InMemoryAgentSession.of("run-idle");

        assertFalse(SessionRuns.isBusy(session));
        assertEquals(0, SessionRuns.activeCount(session));
        assertFalse(SessionRuns.cancelAll(session));
    }

    @Test
    @DisplayName("登记后可被取消，且只取消一次")
    void cancelAllDisposesAttachedRun() {
        AgentSession session = InMemoryAgentSession.of("run-cancel");
        AtomicInteger disposed = new AtomicInteger();

        assertTrue(SessionRuns.attach(session, handle(disposed)));
        assertTrue(SessionRuns.isBusy(session));

        assertTrue(SessionRuns.cancelAll(session));
        assertEquals(1, disposed.get());
        assertFalse(SessionRuns.isBusy(session));

        // 已无活跃流：再次取消不得重复 dispose
        assertFalse(SessionRuns.cancelAll(session));
        assertEquals(1, disposed.get());
    }

    @Test
    @DisplayName("注销按值：先结束的流不得摘掉仍在跑的流")
    void detachIsValueBased() {
        AgentSession session = InMemoryAgentSession.of("run-detach");
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        Disposable runA = handle(a);
        Disposable runB = handle(b);

        SessionRuns.attach(session, runA);
        SessionRuns.attach(session, runB);
        assertEquals(2, SessionRuns.activeCount(session));

        // A 先结束
        SessionRuns.detach(session, runA);
        assertEquals(1, SessionRuns.activeCount(session), "仍在跑的 B 不能被一并摘掉");
        assertTrue(SessionRuns.isBusy(session));

        // 取消时 B 必须仍可被取消
        assertTrue(SessionRuns.cancelAll(session));
        assertEquals(1, b.get());
        assertEquals(0, a.get(), "A 已自行结束，不应被再次 dispose");
    }

    @Test
    @DisplayName("注销主流程之外的幂等：重复注销不报错")
    void detachIsIdempotent() {
        AgentSession session = InMemoryAgentSession.of("run-detach-twice");
        Disposable run = handle(new AtomicInteger());

        SessionRuns.attach(session, run);
        SessionRuns.detach(session, run);
        SessionRuns.detach(session, run);
        SessionRuns.detach(session, null);

        assertEquals(0, SessionRuns.activeCount(session));
    }

    @Test
    @DisplayName("取消代数：覆盖「已创建、未订阅」的窗口")
    void attachRejectsRunCancelledBeforeSubscribe() {
        AgentSession session = InMemoryAgentSession.of("run-gen");
        int gen = SessionRuns.cancelCount(session);

        // 宿主把订阅调度到别的线程，期间发生取消
        SessionRuns.cancelAll(session);

        AtomicInteger disposed = new AtomicInteger();
        boolean attached = SessionRuns.attach(session, handle(disposed), gen);

        assertFalse(attached, "过期代数不得登记");
        assertEquals(1, disposed.get(), "过期句柄必须立即 dispose，否则成为取消不掉的幽灵流");
        assertFalse(SessionRuns.isBusy(session));

        // 取消之后新发起的流（取新代数）不受影响
        int gen2 = SessionRuns.cancelCount(session);
        assertTrue(SessionRuns.attach(session, handle(new AtomicInteger()), gen2));
    }

    @Test
    @DisplayName("取消代数可显式查询，供宿主在调度前取基准")
    void cancelCountAdvancesOnCancel() {
        AgentSession session = InMemoryAgentSession.of("run-gen-count");

        int before = SessionRuns.cancelCount(session);
        SessionRuns.cancelAll(session);

        assertEquals(before + 1, SessionRuns.cancelCount(session));
        assertTrue(SessionRuns.isCancelledSince(session, before));
        assertFalse(SessionRuns.isCancelledSince(session, SessionRuns.cancelCount(session)));
    }

    @Test
    @DisplayName("cancelActive 不推进取消代数：用于新输入顶掉旧轮次")
    void cancelActiveKeepsGenerationStable() {
        AgentSession session = InMemoryAgentSession.of("run-cancel-active");
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, handle(disposed));

        int gen = SessionRuns.cancelCount(session);
        assertTrue(SessionRuns.cancelActive(session));

        assertEquals(1, disposed.get());
        assertEquals(gen, SessionRuns.cancelCount(session), "顶旧轮次不得让新流误判自己已被取消");
    }

    @Test
    @DisplayName("取消作用域限于本会话")
    void cancelIsSessionScoped() {
        AgentSession one = InMemoryAgentSession.of("run-scope-1");
        AgentSession two = InMemoryAgentSession.of("run-scope-2");
        AtomicInteger kept = new AtomicInteger();

        SessionRuns.attach(one, handle(new AtomicInteger()));
        SessionRuns.attach(two, handle(kept));

        SessionRuns.cancelAll(one);

        assertFalse(SessionRuns.isBusy(one));
        assertTrue(SessionRuns.isBusy(two), "另一会话的运行流不得被波及");
        assertEquals(0, kept.get());
    }

    @Test
    @DisplayName("null 会话安全")
    void nullSessionIsSafe() {
        assertFalse(SessionRuns.isBusy(null));
        assertEquals(0, SessionRuns.activeCount(null));
        assertFalse(SessionRuns.cancelAll(null));
        assertEquals(0, SessionRuns.cancelCount(null));
        assertFalse(SessionRuns.attach(null, handle(new AtomicInteger())));
        SessionRuns.detach(null, null);
    }

    @Test
    @DisplayName("运行结束由 detach 释放：与流 doFinally 一致")
    void detachReleasesBusyState() {
        AgentSession session = InMemoryAgentSession.of("run-passive");
        AtomicInteger disposed = new AtomicInteger();
        Disposable run = handle(disposed);

        SessionRuns.attach(session, run);
        assertTrue(SessionRuns.isBusy(session));

        // 与 stream() 的 doFinally 行为一致
        SessionRuns.detach(session, run);
        assertEquals(0, SessionRuns.activeCount(session), "注销后不再计入忙碌");
        assertFalse(SessionRuns.isBusy(session));
    }

    @Test
    @DisplayName("claim 是原子认领：第二个认领者拿不到句柄")
    void claimIsAtomic() {
        AgentSession session = InMemoryAgentSession.of("run-claim");
        SessionRuns.attach(session, handle(new AtomicInteger()));

        reactor.core.Disposable.Composite first = SessionRuns.claim(session);
        assertNotNull(first, "首个认领者必须拿到句柄");
        assertNull(SessionRuns.claim(session), "第二个认领者不得再拿到句柄");

        first.dispose();
        assertFalse(SessionRuns.isBusy(session));
    }

    @Test
    @DisplayName("claim 在多线程下只有一个成功")
    void claimIsAtomicUnderRace() throws Exception {
        for (int round = 0; round < 50; round++) {
            AgentSession session = InMemoryAgentSession.of("run-race-" + round);
            SessionRuns.attach(session, handle(new AtomicInteger()));

            int threads = 8;
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        start.await();
                        if (SessionRuns.claim(session) != null) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            start.countDown();
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // 两个并发 Stop 若都认领成功，就会重复写取消消息、重复领 IM 终态
            assertEquals(1, winners.get(), "并发认领必须只有一个成功");
        }
    }
}
