package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.command.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.input.BusyPolicy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.harness.scope.InputScope;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提交选项（忙时策略、提交者主体）与 {@link HarnessInput#copyFor} 的契约：
 *
 * <ol>
 *   <li>{@code REJECT} 在受理的会话锁内判定：并发受理下没有任何一条进积压；</li>
 *   <li>{@code QUEUE}（缺省）与 {@code STEER}（直连 handle 时）照常进积压；</li>
 *   <li>主体不符时受理前拒绝（不占位、不进积压），一致或不传时照常受理；</li>
 *   <li>{@code copyFor} 复制全部字段（反射守卫：以后新增字段漏抄会失败），附件与属性互不影响。</li>
 * </ol>
 */
class HarnessInputSubmitOptionsTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .build();
    }

    // ---------- 1/2. 忙时策略 ----------

    @Test
    void rejectIsDecidedInsideTheSessionLock() throws Exception {
        HarnessEngine engine = engine();
        HandleAdmissionContractTest.StubBacklog backlog = new HandleAdmissionContractTest.StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<InputOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                start.await();
                outcomes.add(engine.handle(HarnessInput.of(session, "msg-" + n).whenBusy(BusyPolicy.REJECT)));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        long tasks = outcomes.stream().filter(o -> o.getKind() == InputOutcome.Kind.TASK).count();
        long busy = outcomes.stream().filter(o -> o.getRejectReason() == RejectReason.BUSY).count();
        assertEquals(1, tasks, outcomes.toString());
        assertEquals(threads - 1, busy, "其余全部当场拒绝");
        assertTrue(backlog.offered.isEmpty(), "REJECT 的输入不得进积压（排着的那一轮没人接）");
        outcomes.stream().filter(o -> o.getKind() == InputOutcome.Kind.TASK).forEach(InputOutcome::discard);
    }

    @Test
    void queueAndSteerFallBackToBacklogOnDirectHandle() {
        HarnessEngine engine = engine();
        HandleAdmissionContractTest.StubBacklog backlog = new HandleAdmissionContractTest.StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome first = engine.handle(HarnessInput.of(session, "first"));
        assertEquals(InputOutcome.Kind.TASK, first.getKind());

        assertEquals(InputOutcome.Kind.DEFERRED, engine.handle(HarnessInput.of(session, "q")).getKind());
        assertEquals(InputOutcome.Kind.DEFERRED,
                engine.handle(HarnessInput.of(session, "s").whenBusy(BusyPolicy.STEER)).getKind(),
                "直连 handle 时 STEER 按 QUEUE 处理");
        assertEquals(2, backlog.offered.size());
        first.discard();
    }

    @Test
    void whenBusyDefaultsToQueue() {
        assertEquals(BusyPolicy.QUEUE, HarnessInput.of("x").getWhenBusy());
        assertEquals(BusyPolicy.QUEUE, HarnessInput.of("x").whenBusy(null).getWhenBusy());
        assertNull(HarnessInput.of("x").getSession());
    }

    // ---------- 3. 提交者主体 ----------

    @Test
    void principalMismatchIsRejectedBeforeAdmission() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        engine.setSessionPrincipal(session, "alice");

        InputOutcome denied = engine.handle(HarnessInput.of(session, "hi").principal("bob"));
        assertEquals(InputOutcome.Kind.REJECTED, denied.getKind());
        assertEquals(RejectReason.SCOPE_UNAVAILABLE, denied.getRejectReason());
        assertEquals("principal mismatch", denied.getRejectDetail());
        assertFalse(engine.status(session).isAdmitting(), "被拒的输入不占位");

        InputOutcome ok = engine.handle(HarnessInput.of(session, "hi").principal("alice"));
        assertEquals(InputOutcome.Kind.TASK, ok.getKind());
        ok.discard();

        InputOutcome unchecked = engine.handle(HarnessInput.of(session, "hi"));
        assertEquals(InputOutcome.Kind.TASK, unchecked.getKind(), "不传主体 = 不校验");
        unchecked.discard();
    }

    @Test
    void principalOnSessionWithoutPrincipalIsRejected() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome denied = engine.handle(HarnessInput.of(session, "hi").principal("bob"));
        assertEquals(RejectReason.SCOPE_UNAVAILABLE, denied.getRejectReason());
    }

    // ---------- 4. copyFor ----------

    @Test
    void copyForCopiesEveryField() throws Exception {
        AgentSession target = InMemoryAgentSession.of("s1");
        HarnessInput src = HarnessInput.of("hello @x")
                .reference("a.txt")
                .selectedModel("m")
                .selectedAgent("a")
                .selectedSkill("k")
                .selectedMcp("p")
                .scope(InputScope.builder("alice").build())
                .commandContextFactory(new BufferedCommandContextFactory())
                .origin(InputOrigin.channel("im"))
                .hitl(HITLAction.APPROVE, "c1")
                .hitlOutput("o")
                .agentNameValidator(n -> true)
                .trimContent(true)
                .explicitAgent("e")
                .commandAware(false)
                .agentAware(false)
                .terminal(true)
                .resume(true)
                .whenBusy(BusyPolicy.REJECT)
                .principal("alice")
                .attr("k", "v");
        Field program = HarnessInput.class.getDeclaredField("program");
        program.setAccessible(true);
        program.setBoolean(src, true);
        Field restored = HarnessInput.class.getDeclaredField("scopeRestored");
        restored.setAccessible(true);
        restored.setBoolean(src, true);
        Field resolved = HarnessInput.class.getDeclaredField("scopeResolved");
        resolved.setAccessible(true);
        resolved.setBoolean(src, true);

        HarnessInput copy = src.copyFor(target);
        HarnessInput defaults = HarnessInput.of("hello @x");
        assertSame(target, copy.getSession());

        for (Field f : HarnessInput.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("session")) {
                continue;
            }
            f.setAccessible(true);
            Object a = f.get(src);
            Object b = f.get(copy);
            assertNotNull(a, "测试未给字段赋非缺省值：" + f.getName());
            if (f.getType().isPrimitive() || f.getType().isEnum() || f.getType() == InputOrigin.class) {
                assertNotEquals(f.get(defaults), a, "测试未给字段赋非缺省值：" + f.getName());
            }
            if (f.getName().equals("attachments")) {
                assertNotSame(a, b, "附件需复制一份");
                assertEquals(src.getAttachments().getReferences(), copy.getAttachments().getReferences());
            } else if (f.getName().equals("attrs")) {
                assertNotSame(a, b, "属性需复制一份");
                assertEquals(a, b);
            } else {
                assertEquals(a, b, "copyFor 漏抄字段：" + f.getName());
            }
        }

        copy.attr("k2", "v2").reference("b.txt");
        assertNull(src.getAttr("k2"), "副本不影响原对象");
        assertEquals(1, src.getAttachments().getReferences().size());
    }
}
