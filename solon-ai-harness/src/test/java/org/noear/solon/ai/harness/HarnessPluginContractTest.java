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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.harness.input.AdmitDecision;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.InputBacklog;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.session.SessionTurn;
import org.noear.solon.ai.harness.session.TurnDispatch;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件化契约（核心侧）：核心只经三种机制与插件协作，且不认识任何具体插件。
 *
 * <ol>
 *   <li>钩子 {@link HarnessExtension#onInterrupt}：用户级中断在停流之前回调、异常隔离、只停流的程序中断不回调；</li>
 *   <li>能力接口 {@link InputBacklog}：{@code getExtension(接口)} 可查、{@code status} 求和、
 *       {@code admit} 忙态有积压能力 → QUEUE，没有 → REJECT(busy)；</li>
 *   <li>会话槽位 {@link TurnDispatch}：admit / SessionTurn 只认接口；</li>
 *   <li>装配 {@link HarnessEngine#addExtensionIfAbsent}：按类型幂等；</li>
 *   <li>守卫：核心 main 源码不得 import 任何插件包。</li>
 * </ol>
 */
class HarnessPluginContractTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider(InMemoryAgentSession::of)
                .build();
    }

    private static AtomicInteger busy(AgentSession session) {
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, new Disposable() {
            @Override
            public void dispose() {
                disposed.incrementAndGet();
            }

            @Override
            public boolean isDisposed() {
                return disposed.get() > 0;
            }
        });
        return disposed;
    }

    /** 积压能力桩：只记数，不认识队列。 */
    static class StubBacklog implements HarnessExtension, InputBacklog {
        final List<String> offered = new ArrayList<>();
        int interrupts;
        boolean busyAtInterrupt;

        @Override
        public int offer(AgentSession session, String text, InputOrigin origin) {
            offered.add(text);
            return offered.size();
        }

        @Override
        public int size(AgentSession session) {
            return offered.size();
        }

        @Override
        public void onInterrupt(HarnessEngine engine, AgentSession session) {
            interrupts++;
            busyAtInterrupt = SessionRuns.isBusy(session);
            offered.clear();
        }
    }

    /** 派发事务桩。 */
    static class StubDispatch implements TurnDispatch {
        final Thread thread = Thread.currentThread();
        final AgentSession session;
        Boolean settled;

        StubDispatch(AgentSession session) {
            this.session = session;
        }

        @Override
        public InputOrigin getOrigin() {
            return InputOrigin.channel("stub");
        }

        @Override
        public boolean isOnDispatchThread() {
            return Thread.currentThread() == thread;
        }

        @Override
        public boolean isCurrent() {
            return TurnDispatch.current(session) == this;
        }

        @Override
        public boolean settle(boolean started) {
            settled = started;
            return TurnDispatch.unbind(session, this);
        }
    }

    // ==================== 钩子：onInterrupt ====================

    @Test
    void onInterruptRunsBeforeStreamIsStopped() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);

        AgentSession session = InMemoryAgentSession.of();
        backlog.offer(session, "排队一", InputOrigin.NONE);
        AtomicInteger disposed = busy(session);

        assertTrue(engine.interrupt(session));
        assertEquals(1, backlog.interrupts);
        assertTrue(backlog.busyAtInterrupt, "必须先清积压再停流（回调时流仍在）");
        assertEquals(1, disposed.get());
        assertEquals(0, engine.status(session).getQueuedCount());
    }

    @Test
    void onInterruptAlsoRunsWhenNoStreamAndForTwoPhase() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();
        backlog.offer(session, "排队一", InputOrigin.NONE);

        assertFalse(engine.interrupt(session), "无运行流时返回 false");
        assertEquals(1, backlog.interrupts, "没有流也要清积压");

        backlog.offer(session, "排队二", InputOrigin.NONE);
        busy(session);
        try (org.noear.solon.ai.harness.session.interrupt.InterruptPlan plan = engine.prepareInterrupt(session)) {
            assertEquals(2, backlog.interrupts, "两段式的第一段就回调");
            assertEquals(0, backlog.size(session));
            assertTrue(plan.hasActiveRun());
        }
    }

    @Test
    void failingExtensionDoesNotBlockInterrupt() {
        HarnessEngine engine = engine();
        engine.addExtension(new HarnessExtension() {
            @Override
            public void onInterrupt(HarnessEngine engine, AgentSession session) {
                throw new IllegalStateException("boom");
            }
        });
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);

        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = busy(session);

        assertTrue(engine.interrupt(session), "单个扩展异常不得阻止中断生效");
        assertEquals(1, disposed.get());
        assertEquals(1, backlog.interrupts, "后续扩展仍被回调");
    }

    @Test
    void streamOnlyInterruptsDoNotNotifyExtensions() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();

        busy(session);
        engine.interruptStream(session);
        busy(session);
        engine.interruptStreamActive(session);

        assertEquals(0, backlog.interrupts, "只停流是程序行为，不通知插件");
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void interruptWithNullSessionIsSafe() {
        assertFalse(engine().interrupt(null));
        assertNull(engine().prepareInterrupt(null));
    }

    // ==================== 能力：InputBacklog ====================

    @Test
    void capabilityLookupAndStatusSum() {
        HarnessEngine engine = engine();
        assertFalse(engine.getExtension(InputBacklog.class).isPresent());

        StubBacklog a = new StubBacklog();
        StubBacklog b = new StubBacklog();
        engine.addExtension(a);
        engine.addExtension(b);
        assertSame(a, engine.getExtension(InputBacklog.class).orElse(null), "按能力接口查找首个实现");

        AgentSession session = InMemoryAgentSession.of();
        a.offer(session, "1", InputOrigin.NONE);
        b.offer(session, "2", InputOrigin.NONE);
        b.offer(session, "3", InputOrigin.NONE);

        assertEquals(3, engine.status(session).getQueuedCount(), "积压数 = 各积压能力之和");
        assertEquals(0, SessionStatus.of(session).getQueuedCount(), "静态 of 只读核心事实");
    }

    @Test
    void admitQueuesWhenBusyOnlyIfBacklogPresent() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        AdmitDecision noBacklog = engine.admit(session, "hello", false, null);
        assertEquals(AdmitDecision.Kind.REJECT, noBacklog.getKind(), "没装积压能力时无队可入");
        assertEquals("busy", noBacklog.getRejectReason());

        engine.addExtension(new StubBacklog());
        assertEquals(AdmitDecision.Kind.QUEUE, engine.admit(session, "hello", false, null).getKind());
    }

    @Test
    void admitQueuesWhenIdleButBacklogNotEmpty() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();
        backlog.offer(session, "先来的", InputOrigin.NONE);

        assertEquals(AdmitDecision.Kind.QUEUE, engine.admit(session, "后来的", false, null).getKind(),
                "有积压时空闲也要排队（先进先出）");
    }

    // ==================== 槽位：TurnDispatch ====================

    @Test
    void turnDispatchSlotDrivesAdmitAndSessionTurn() {
        HarnessEngine engine = engine();
        engine.addExtension(new StubBacklog());
        AgentSession session = InMemoryAgentSession.of();

        StubDispatch dispatch = new StubDispatch(session);
        assertNull(TurnDispatch.bind(session, dispatch));
        assertSame(dispatch, session.attrs().get(HarnessAttrs.TURN_DISPATCH));

        AdmitDecision admit = engine.admit(session, "派发的一轮", false, null);
        assertEquals(AdmitDecision.Kind.ADMIT_BYPASS, admit.getKind());
        assertTrue(admit.isQueueDispatch());
        assertSame(dispatch, admit.getDispatch());

        SessionTurn turn = SessionTurn.begin(session);
        assertSame(dispatch, turn.dispatch());
        assertTrue(turn.settle(true));
        assertEquals(Boolean.TRUE, dispatch.settled);
        assertNull(TurnDispatch.current(session), "收口后注销");
    }

    @Test
    void unbindOnlyRemovesOwnDispatch() {
        AgentSession session = InMemoryAgentSession.of();
        StubDispatch mine = new StubDispatch(session);
        StubDispatch other = new StubDispatch(session);
        TurnDispatch.bind(session, mine);

        assertFalse(TurnDispatch.unbind(session, other), "不得抹掉别人的登记");
        assertSame(mine, TurnDispatch.current(session));
        assertSame(mine, TurnDispatch.bind(session, other), "覆盖返回旧值供登记方告警");
        assertTrue(TurnDispatch.unbind(session, null), "null = 无条件清除");
        assertNull(TurnDispatch.current(session));
    }

    // ==================== 装配：addExtensionIfAbsent ====================

    @Test
    void addExtensionIfAbsentIsIdempotentByType() {
        HarnessEngine engine = engine();
        AtomicInteger attached = new AtomicInteger();

        class Counting implements HarnessExtension {
            @Override
            public void onAttach(HarnessEngine engine) {
                attached.incrementAndGet();
            }
        }

        Counting first = new Counting();
        assertSame(first, engine.addExtensionIfAbsent(first));
        assertSame(first, engine.addExtensionIfAbsent(new Counting()), "已有同类型时返回已挂载者");
        assertEquals(1, attached.get(), "不得二次 onAttach");
        assertNull(engine.addExtensionIfAbsent(null));
    }

    // ==================== 守卫：核心不认识插件 ====================

    @Test
    void coreMainSourcesDoNotImportPluginPackages() throws Exception {
        Path root = Paths.get("src/main/java");
        AtomicBoolean scanned = new AtomicBoolean();
        List<String> violations;
        try (Stream<Path> files = Files.walk(root)) {
            violations = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        scanned.set(true);
                        try {
                            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            return src.matches("(?s).*import\\s+(static\\s+)?org\\.noear\\.solon\\.ai\\.harness\\.session\\.(queue|steer|goal)\\..*");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(p -> root.relativize(p).toString())
                    .collect(Collectors.toList());
        }

        assertTrue(scanned.get(), "守卫必须真的扫描到源码（防止路径错误导致假通过）");
        assertTrue(violations.isEmpty(), "核心模块不得 import 插件包：" + violations);
    }
}
