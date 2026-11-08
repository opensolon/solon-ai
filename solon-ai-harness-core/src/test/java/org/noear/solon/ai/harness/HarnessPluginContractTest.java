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
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.spi.RunDispatch;
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
 *   <li>钩子 {@link HarnessExtension#onSessionInterrupt}：用户级中断在停流之前回调、异常隔离、只停流的程序中断不回调；</li>
 *   <li>钩子 {@link HarnessExtension#contributeSessionStatus}：扩展事实经开放槽位贡献、按键合并、异常隔离、不判开关；</li>
 *   <li>能力接口 {@link InputBacklog}：{@code getExtension(接口)} 可查、
 *       {@code admit} 忙态有积压能力 → QUEUE，没有 → REJECT(busy)；</li>
 *   <li>会话槽位 {@link RunDispatch}：admit / RunTicket 只认接口；</li>
 *   <li>装配 {@link HarnessEngine#addExtensionIfAbsent}：按类型幂等；</li>
 *   <li>守卫：核心 main 源码不得 import 任何插件包。</li>
 * </ol>
 */
class HarnessPluginContractTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        // 假模型（不可达地址）：受理判定类用例需要装配出请求，但不开流
        org.noear.solon.ai.chat.ChatConfig model = new org.noear.solon.ai.chat.ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
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

    /** 积压桩自定义的事实键（核心不认识它） */
    static final SessionStatus.Key<Integer> STUB_PENDING = SessionStatus.Key.count("stub.backlog");

    /** 积压能力桩：只记数，不认识队列。 */
    static class StubBacklog implements HarnessExtension, InputBacklog {
        final List<String> offered = new ArrayList<>();
        int interrupts;
        boolean busyAtInterrupt;

        @Override
        public int offer(AgentSession session, HarnessInput input) {
            offered.add(input.getText());
            return offered.size();
        }

        int offer(AgentSession session, String text) {
            return offer(session, HarnessInput.of(session, text));
        }

        /** 内核受理靠的是能力方法（类型系统保证），不是状态键 */
        @Override
        public boolean hasPending(AgentSession session) {
            return offered.isEmpty() == false;
        }

        /** 展示用的数量是插件自有事实：键由插件自己定义 */
        @Override
        public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder status) {
            status.put(STUB_PENDING, offered.size());
        }

        @Override
        public void onSessionInterrupt(HarnessEngine engine, AgentSession session) {
            interrupts++;
            busyAtInterrupt = SessionRuns.isBusy(session);
            offered.clear();
        }
    }

    /** 派发事务桩。 */
    static class StubDispatch implements RunDispatch {
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
            return RunDispatch.current(session) == this;
        }

        @Override
        public boolean settle(boolean started) {
            settled = started;
            return RunDispatch.unbind(session, this);
        }
    }

    // ==================== 钩子：onSessionInterrupt ====================

    @Test
    void onSessionInterruptRunsBeforeStreamIsStopped() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);

        AgentSession session = InMemoryAgentSession.of();
        backlog.offer(session, "排队一");
        AtomicInteger disposed = busy(session);

        assertTrue(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue());
        assertEquals(1, backlog.interrupts);
        assertTrue(backlog.busyAtInterrupt, "必须先清积压再停流（回调时流仍在）");
        assertEquals(1, disposed.get());
        assertEquals(0, (int) engine.status(session).get(STUB_PENDING));
        assertTrue(engine.isIdle(session), "积压已清、流已停：空闲");
    }

    @Test
    void onSessionInterruptAlsoRunsWhenNoStreamAndForTwoPhase() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();
        backlog.offer(session, "排队一");

        assertFalse(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue(), "无运行流时返回 false");
        assertEquals(1, backlog.interrupts, "没有流也要清积压");

        backlog.offer(session, "排队二");
        busy(session);
        try (org.noear.solon.ai.harness.InterruptPlan plan = engine.prepareInterrupt(session)) {
            assertEquals(2, backlog.interrupts, "两段式的第一段就回调");
            assertEquals(0, backlog.offered.size());
            assertTrue(plan.hasActiveRun());
        }
    }

    @Test
    void failingExtensionDoesNotBlockInterrupt() {
        HarnessEngine engine = engine();
        engine.addExtension(new HarnessExtension() {
            @Override
            public void onSessionInterrupt(HarnessEngine engine, AgentSession session) {
                throw new IllegalStateException("boom");
            }
        });
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);

        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger disposed = busy(session);

        assertTrue(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue(), "单个扩展异常不得阻止中断生效");
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
        engine.interrupt(session, InterruptMode.CANCEL_STREAM);
        busy(session);
        engine.interrupt(session, InterruptMode.PREEMPT_STREAM);

        assertEquals(0, backlog.interrupts, "只停流是程序行为，不通知插件");
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void interruptWithNullSessionIsSafe() {
        assertFalse(engine().interrupt(null, InterruptMode.INTERRUPT_ALL).getValue());
        assertNull(engine().prepareInterrupt(null));
    }

    // ==================== 钩子：onSessionRemove ====================

    /** 记录 onSessionRemove 的扩展桩 */
    static class RemoveRecorder extends AbsHarnessExtension {
        final List<String> removed = new ArrayList<>();

        @Override
        public void onSessionRemove(HarnessEngine engine, String sessionId) {
            removed.add(sessionId);
        }
    }

    @Test
    void removeSessionNotifiesAllExtensionsEvenDisabledAndIsolatesFailures() {
        HarnessEngine engine = engine();
        RemoveRecorder disabled = new RemoveRecorder();
        disabled.setEnabled(false);
        engine.addExtension(disabled);
        engine.addExtension(new HarnessExtension() {
            @Override
            public void onSessionRemove(HarnessEngine engine, String sessionId) {
                throw new IllegalStateException("boom");
            }
        });
        RemoveRecorder after = new RemoveRecorder();
        engine.addExtension(after);

        engine.removeSession("s1");

        assertEquals(java.util.Collections.singletonList("s1"), disabled.removed, "清理类钩子不判开关");
        assertEquals(java.util.Collections.singletonList("s1"), after.removed, "单个扩展异常不影响后续扩展");
    }

    @Test
    void removeSessionIgnoresBlankIdAndNeverTouchesSessionProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> {
                    providerCalls.incrementAndGet();
                    return InMemoryAgentSession.of(id);
                })
                .build();
        RemoveRecorder recorder = new RemoveRecorder();
        engine.addExtension(recorder);
        int baseline = providerCalls.get();

        engine.removeSession(null);
        engine.removeSession("");
        assertTrue(recorder.removed.isEmpty(), "空 ID 直接返回");

        engine.removeSession("s1");
        assertEquals(1, recorder.removed.size());
        assertEquals(baseline, providerCalls.get(), "删除流程不得取会话（会把会话重建出来）");
    }

    @Test
    void getSessionOrNullDoesNotCreate() {
        java.util.Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .build();

        assertNull(engine.getSessionOrNull("s1"));
        assertNull(engine.getSessionOrNull(null));
        assertNull(engine.getSessionOrNull(""));
        assertTrue(sessions.isEmpty(), "取不到时不得新建");

        AgentSession created = engine.getSession("s1");
        assertSame(created, engine.getSessionOrNull("s1"));
    }

    // ==================== 钩子：contributeSessionStatus（开放事实槽位） ====================

    /** 插件自定义的事实键（核心不认识它） */
    static final SessionStatus.Key<String> PLUGIN_PHASE = SessionStatus.Key.of("stub.phase", "idle");
    static final SessionStatus.Key<Integer> PLUGIN_COUNT = SessionStatus.Key.count("stub.count");

    static class StubFacts extends AbsHarnessExtension {
        final String phase;
        final int count;

        StubFacts(String phase, int count) {
            this.phase = phase;
            this.count = count;
        }

        @Override
        public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder status) {
            status.put(PLUGIN_PHASE, phase).put(PLUGIN_COUNT, count);
        }
    }

    @Test
    void extensionsContributeFactsThroughOpenSlots() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        SessionStatus bare = engine.status(session);
        assertEquals("idle", bare.get(PLUGIN_PHASE), "无人贡献时取键的缺省值");
        assertEquals(0, (int) bare.get(PLUGIN_COUNT));
        assertFalse(bare.has(PLUGIN_PHASE));

        engine.addExtension(new StubFacts("running", 2));
        engine.addExtension(new StubFacts("paused", 3));

        SessionStatus status = engine.status(session);
        assertTrue(status.has(PLUGIN_PHASE));
        assertEquals("paused", status.get(PLUGIN_PHASE), "单一拥有者键：后写覆盖");
        assertEquals(5, (int) status.get(PLUGIN_COUNT), "计数键：多提供者求和");
        assertTrue(status.toString().contains("stub.count=5"), status.toString());

        SessionStatus core = SessionStatus.of(session);
        assertEquals("idle", core.get(PLUGIN_PHASE), "静态 of 只读核心事实");
    }

    @Test
    void statusFactsIgnoreSwitchAndIsolateFailures() {
        HarnessEngine engine = engine();
        engine.addExtension(new HarnessExtension() {
            @Override
            public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder status) {
                throw new IllegalStateException("boom");
            }
        });
        StubFacts disabled = new StubFacts("parked", 1);
        disabled.setEnabled(false);
        engine.addExtension(disabled);

        SessionStatus status = engine.status(InMemoryAgentSession.of());
        assertEquals("parked", status.get(PLUGIN_PHASE), "事实类钩子不判开关，且单个异常不影响其余");
        assertFalse(engine.status(null).has(PLUGIN_PHASE), "null 会话不回调扩展");
    }

    @Test
    void countKeyClampsNegativeAndIgnoresNull() {
        SessionStatus status = SessionStatus.builder(null)
                .put(PLUGIN_COUNT, -4)
                .put(PLUGIN_COUNT, 2)
                .put(PLUGIN_PHASE, null)
                .build();
        assertEquals(2, (int) status.get(PLUGIN_COUNT), "负数按 0 计");
        assertFalse(status.has(PLUGIN_PHASE), "null 贡献忽略");
    }

    @Test
    void sameNameDifferentKeysDoNotCollide() {
        SessionStatus.Key<Integer> other = SessionStatus.Key.count("stub.count");
        SessionStatus status = SessionStatus.builder(null).put(PLUGIN_COUNT, 1).put(other, 7).build();
        assertEquals(1, (int) status.get(PLUGIN_COUNT), "键按实例身份区分");
        assertEquals(7, (int) status.get(other));
    }

    // ==================== 能力：InputBacklog ====================

    @Test
    void capabilityLookupAndPluginOwnedFacts() {
        HarnessEngine engine = engine();
        assertFalse(engine.getExtension(InputBacklog.class).isPresent());

        StubBacklog a = new StubBacklog();
        StubBacklog b = new StubBacklog();
        engine.addExtension(a);
        engine.addExtension(b);
        assertSame(a, engine.getExtension(InputBacklog.class).orElse(null), "按能力接口查找首个实现");

        AgentSession session = InMemoryAgentSession.of();
        a.offer(session, "1");
        b.offer(session, "2");
        b.offer(session, "3");

        assertEquals(3, (int) engine.status(session).get(STUB_PENDING), "插件自有计数键：多提供者求和");
        assertFalse(SessionStatus.of(session).has(STUB_PENDING), "静态 of 只读核心事实");
    }

    @Test
    void isIdleCombinesOccupancyAndAnyBacklog() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        assertFalse(engine.isIdle(null));
        assertTrue(engine.isIdle(session), "无积压能力、无运行流：空闲");

        StubBacklog a = new StubBacklog();
        StubBacklog b = new StubBacklog();
        engine.addExtension(a);
        engine.addExtension(b);
        assertTrue(engine.isIdle(session));

        b.offer(session, "排在后面的积压");
        assertFalse(engine.isIdle(session), "任一积压能力有待执行项即不空闲（不只看首个）");
        b.offered.clear();

        busy(session);
        assertFalse(engine.isIdle(session), "有运行流：不空闲");
    }

    @Test
    void failingBacklogIsIsolated() {
        HarnessEngine engine = engine();
        class Broken extends StubBacklog {
            @Override
            public boolean hasPending(AgentSession session) {
                throw new IllegalStateException("boom");
            }
        }
        engine.addExtension(new Broken());
        AgentSession session = InMemoryAgentSession.of();
        assertTrue(engine.isIdle(session), "单个积压能力异常按无积压计");
        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        outcome.discard();
    }

    @Test
    void admitDefersWhenBusyOnlyIfBacklogPresent() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        InputOutcome noBacklog = engine.handle(HarnessInput.of(session, "hello"));
        assertEquals(InputOutcome.Kind.REJECTED, noBacklog.getKind(), "没装积压能力时无处可延");
        assertEquals(InputOutcome.RejectReason.BUSY, noBacklog.getRejectReason());
        assertEquals("busy", noBacklog.getRejectReason().code(), "线上协议值保持小写");

        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        InputOutcome deferred = engine.handle(HarnessInput.of(session, "hello"));
        assertEquals(InputOutcome.Kind.DEFERRED, deferred.getKind());
        assertEquals(1, deferred.getBacklogPosition());
        assertEquals("hello", backlog.offered.get(0), "引擎自己入积压，宿主不再 enqueue");
    }

    @Test
    void admitDefersWhenIdleButBacklogNotEmpty() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();
        backlog.offer(session, "先来的");

        assertEquals(InputOutcome.Kind.DEFERRED, engine.handle(HarnessInput.of(session, "后来的")).getKind(),
                "有积压时空闲也要排队（先进先出）");
    }

    // ==================== 槽位：RunDispatch ====================

    @Test
    void runDispatchSlotDrivesAdmitAndRunTicket() {
        HarnessEngine engine = engine();
        engine.addExtension(new StubBacklog());
        AgentSession session = InMemoryAgentSession.of();

        StubDispatch dispatch = new StubDispatch(session);
        assertNull(RunDispatch.bind(session, dispatch));
        assertSame(dispatch, session.attrs().get(HarnessAttrs.RUN_DISPATCH));

        InputOutcome admit = engine.handle(HarnessInput.of(session, "派发的一轮"));
        assertEquals(InputOutcome.Kind.TASK, admit.getKind(), "程序派发的一轮天然空闲");
        assertTrue(admit.isDispatched());
        assertSame(dispatch, admit.getDispatch());
        assertTrue(engine.status(session).isAdmitting(), "派发轮次的 TASK 同样占位到开流（派发方据此判断已异步开流）");
        admit.discard();
        assertFalse(engine.status(session).isAdmitting());

        RunTicket ticket = RunTicket.begin(session);
        assertSame(dispatch, ticket.dispatch());
        assertTrue(ticket.settle(true));
        assertEquals(Boolean.TRUE, dispatch.settled);
        assertNull(RunDispatch.current(session), "收口后注销");
    }

    @Test
    void unbindOnlyRemovesOwnDispatch() {
        AgentSession session = InMemoryAgentSession.of();
        StubDispatch mine = new StubDispatch(session);
        StubDispatch other = new StubDispatch(session);
        RunDispatch.bind(session, mine);

        assertFalse(RunDispatch.unbind(session, other), "不得抹掉别人的登记");
        assertSame(mine, RunDispatch.current(session));
        assertSame(mine, RunDispatch.bind(session, other), "覆盖返回旧值供登记方告警");
        assertTrue(RunDispatch.unbind(session, null), "null = 无条件清除");
        assertNull(RunDispatch.current(session));
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

    @Test
    void sameInstanceIsAttachedOnlyOnce() {
        AtomicInteger attached = new AtomicInteger();

        class Counting implements HarnessExtension {
            @Override
            public void onAttach(HarnessEngine engine) {
                attached.incrementAndGet();
            }
        }

        // 构建期：同一实例重复 extensionAdd 只算一次；null 忽略
        Counting one = new Counting();
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .extensionAdd(one)
                .extensionAdd(one)
                .extensionAdd(null)
                .build();
        assertEquals(1, attached.get());
        assertEquals(1, engine.getExtensions().stream().filter(e -> e == one).count());

        // 运行期：同一实例再 addExtension 不二次 onAttach；同类另一个实例照常挂载
        engine.addExtension(one);
        assertEquals(1, attached.get(), "同一实例不得二次 onAttach");
        engine.addExtension(new Counting());
        assertEquals(2, attached.get(), "同类不同实例照常挂载");
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
                            return src.matches("(?s).*import\\s+(static\\s+)?org\\.noear\\.solon\\.ai\\.harness\\.session\\.(queue|steer|goal|loop)\\..*");
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

    /**
     * 内核没有 queue：核心对外类型（快照、受理结论、能力接口）的公开成员与枚举值不得出现 queue 语义。
     * 插件事实键一律由插件定义（如 QueueExtension.STATUS_PENDING），内核判定走能力接口方法。
     */
    @Test
    void corePublicApiHasNoQueueVocabulary() {
        Class<?>[] types = {SessionStatus.class, SessionStatus.Builder.class, SessionStatus.Key.class,
                InputOutcome.class, InputOutcome.Kind.class, InputBacklog.class, RunDispatch.class,
                HarnessExtension.class, HarnessAttrs.class};
        List<String> violations = new ArrayList<>();
        for (Class<?> type : types) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                if (m.getName().toLowerCase().contains("queue")) {
                    violations.add(type.getSimpleName() + "#" + m.getName());
                }
            }
            for (java.lang.reflect.Field f : type.getFields()) {
                if (f.getName().toLowerCase().contains("queue")) {
                    violations.add(type.getSimpleName() + "." + f.getName());
                }
            }
        }
        for (java.lang.reflect.Method m : HarnessEngine.class.getMethods()) {
            if (m.getName().toLowerCase().contains("queue")) {
                violations.add("HarnessEngine#" + m.getName());
            }
        }
        assertTrue(violations.isEmpty(), "核心公开 API 不得含 queue 语义：" + violations);
    }
}
