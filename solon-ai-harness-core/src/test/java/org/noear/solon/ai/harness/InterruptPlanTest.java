/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.message.AssistantMessage;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InterruptPlan} 两段式中断契约。
 *
 * <p>锁定四点（对应宿主 Web 端的编排诉求）：</p>
 * <ol>
 *   <li>两段等价性：prepare + commit 与一步式中断（claim + commit）副作用一致；</li>
 *   <li>认领原子性：两个并发 prepare 只有一者拿到 {@code hasActiveRun()==true}；</li>
 *   <li>commit 幂等：重复 commit / close 不重复停流、不重复写取消消息；</li>
 *   <li>close 兜底：宿主忘记 commit 时，凭证闭合仍会停流（不泄漏孤儿流）。</li>
 * </ol>
 *
 * <p>扩展的中断参与（如清队列）由引擎在第一段之前回调，见 {@code HarnessPluginContractTest}。</p>
 */
class InterruptPlanTest {

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
    void prepareThenCommitMatchesOneStepInterrupt() {
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, countingDisposable(disposed));

        InterruptPlan plan = HarnessInterrupts.claim(session);
        assertTrue(plan != null && plan.hasActiveRun());

        // 编排窗口内：流尚未停，取消消息尚未写
        assertEquals(0, disposed.get(), "第一段不得停流（窗口内宿主还要发事件）");
        assertTrue(session.getMessages().isEmpty(), "第一段不得写取消消息（落库时机归宿主）");

        assertTrue(plan.commit());
        assertEquals(1, disposed.get(), "提交后停流恰一次");
        assertEquals(1, session.getMessages().size(), "提交后补写取消消息恰一条");
        assertFalse(SessionRuns.isBusy(session));
    }

    @Test
    void concurrentPrepareYieldsExactlyOneActiveRun() throws InterruptedException {
        AgentSession session = InMemoryAgentSession.of();
        SessionRuns.attach(session, countingDisposable(new AtomicInteger()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger activeClaims = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    InterruptPlan plan = HarnessInterrupts.claim(session);
                    if (plan != null && plan.hasActiveRun()) {
                        activeClaims.incrementAndGet();
                        plan.commit();
                    } else if (plan != null) {
                        plan.close();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }).start();
        }

        ready.await();
        go.countDown();
        finished.await();

        assertEquals(1, activeClaims.get(), "并发的两次停止只允许一者拿到活跃流（认领原子性）");
        assertEquals(1, session.getMessages().size(), "取消消息不得重复写");
    }

    @Test
    void commitIsIdempotent() {
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, countingDisposable(disposed));

        InterruptPlan plan = HarnessInterrupts.claim(session);
        assertTrue(plan.commit());
        assertFalse(plan.commit(), "重复 commit 必须返回 false 且无副作用");
        assertEquals(1, disposed.get());
        assertEquals(1, session.getMessages().size());

        plan.close();
        assertEquals(1, disposed.get(), "提交后的 close 是空操作");
        assertEquals(1, session.getMessages().size());
    }

    @Test
    void closeIsFallbackCommit() {
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, countingDisposable(disposed));

        InterruptPlan plan = HarnessInterrupts.claim(session);
        // 宿主编排中抛异常、忘记 commit：try-with-resources 的 close 仍会停流
        plan.close();

        assertEquals(1, disposed.get(), "close 与 commit 等价：不泄漏孤儿流");
        assertEquals(1, session.getMessages().size());
        assertFalse(SessionRuns.isBusy(session));
    }

    @Test
    void noActiveRunPlanGoesEmptyPath() {
        AgentSession session = InMemoryAgentSession.of();

        InterruptPlan plan = HarnessInterrupts.claim(session);

        assertTrue(plan != null);
        assertFalse(plan.hasActiveRun(), "无活跃流：宿主应走空态出口（如兜底 done）");
        assertFalse(plan.commit(), "无活跃流的 commit 返回 false");
        assertTrue(session.getMessages().isEmpty(), "空态不得写取消消息（避免污染空会话）");
    }

    @Test
    void nullSessionReturnsNull() {
        assertNull(HarnessInterrupts.claim(null));
    }

    @Test
    void tryWithResourcesClosesWithoutDuplication() {
        // 回归锁：try-with-resources 闭合与一步式等价，取消消息无重复副作用
        AgentSession session = InMemoryAgentSession.of();
        SessionRuns.attach(session, countingDisposable(new AtomicInteger()));

        try (InterruptPlan plan = HarnessInterrupts.claim(session)) {
            assertTrue(plan.hasActiveRun());
        }

        assertEquals(1, session.getMessages().size());
        assertTrue(session.getMessages().get(0) instanceof AssistantMessage);
        assertEquals("用户已取消任务.", session.getMessages().get(0).getContent());
    }

    @Test
    void writeCancelEarlyIsIdempotentAndCommitSkipsIt() {
        // 回归锁：Web 端在编排窗口内提前落库时，commit 不得再写一次（一次中断只落盘一次）
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, countingDisposable(disposed));

        InterruptPlan plan = HarnessInterrupts.claim(session);
        assertTrue(plan.writeCancelEarly(), "窗口内早写：首次应真正写入");
        assertFalse(plan.writeCancelEarly(), "重复早写：幂等返回 false");
        assertEquals(1, session.getMessages().size(), "早写恰好落一条取消消息");

        assertTrue(plan.commit());
        assertEquals(1, session.getMessages().size(), "commit 必须感知已写而跳过（不得重复落盘）");
        assertEquals(1, disposed.get());
    }

    @Test
    void writeCancelEarlyIsNoopWithoutActiveRun() {
        AgentSession session = InMemoryAgentSession.of();
        InterruptPlan plan = HarnessInterrupts.claim(session);

        assertFalse(plan.writeCancelEarly(), "无活跃流不得写取消消息（空会话不被污染）");
        assertTrue(session.getMessages().isEmpty());
        plan.close();
    }
}
