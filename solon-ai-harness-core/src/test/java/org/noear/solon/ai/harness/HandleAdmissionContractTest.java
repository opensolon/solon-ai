package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.builtin.InterruptCommand;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.spi.InputBacklog;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code handle} 唯一执行入口的受理契约（第 0 步：判定、占位、入积压同一把会话锁）。
 *
 * <ol>
 *   <li>并发进入 handle：只有一条得到 TASK，其余 DEFERRED（有积压）或 REJECTED busy（无积压）；</li>
 *   <li>TASK 的占位跟着请求走：订阅调度到别的线程时，订阅前第二条仍判忙；订阅时运行流先登记、占位后释放，无空档；</li>
 *   <li>有待审批卡：普通输入 REJECTED hitl_pending，审批决策照常；</li>
 *   <li>忙时 {@code /interrupt}（runnableWhenBusy）立即执行，其它命令排队；</li>
 *   <li>拿到 TASK 不开流：discard / 中断 / 时限任一都能让会话恢复；</li>
 *   <li>程序轮次忙时 REJECTED，不进积压；</li>
 *   <li>命令内部再次 handle 不会被外层占位拒掉；</li>
 *   <li>非 TASK 结局返回前已释放占位。</li>
 * </ol>
 */
class HandleAdmissionContractTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        return builder().build();
    }

    /** 假模型（指向不可达地址）：只为装配出请求，开流会很快失败。 */
    private HarnessEngine.Builder builder() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake");
    }

    private static void busy(AgentSession session) {
        SessionRuns.attach(session, new Disposable() {
            volatile boolean disposed;

            @Override
            public void dispose() {
                disposed = true;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }
        });
    }

    /** 积压桩：只记录入积压的正文。 */
    static class StubBacklog implements HarnessExtension, InputBacklog {
        final List<String> offered = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int offer(AgentSession session, HarnessInput input) {
            offered.add(input.getText());
            return offered.size();
        }

        @Override
        public boolean hasPending(AgentSession session) {
            return offered.isEmpty() == false;
        }
    }

    // ---------- 1. 并发 ----------

    @Test
    void concurrentHandleHasExactlyOneTask() throws Exception {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
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
                outcomes.add(engine.handle(HarnessInput.of(session, "msg-" + n)));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        long tasks = outcomes.stream().filter(o -> o.getKind() == InputOutcome.Kind.TASK).count();
        long deferred = outcomes.stream().filter(o -> o.getKind() == InputOutcome.Kind.DEFERRED).count();
        assertEquals(1, tasks, "两条输入不得同时开流：" + outcomes);
        assertEquals(threads - 1, deferred);
        assertEquals(threads - 1, backlog.offered.size(), "其余输入由引擎自己入积压");
    }

    @Test
    void busyWithoutBacklogIsRejected() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome first = engine.handle(HarnessInput.of(session, "first"));
        assertEquals(InputOutcome.Kind.TASK, first.getKind());
        assertTrue(engine.status(session).isAdmitting(), "TASK 未开流前会话保持占位");

        InputOutcome second = engine.handle(HarnessInput.of(session, "second"));
        assertEquals(InputOutcome.Kind.REJECTED, second.getKind());
        assertEquals(InputOutcome.RejectReason.BUSY, second.getRejectReason());

        first.discard();
        assertFalse(engine.status(session).isAdmitting(), "discard 立即恢复可受理");
        InputOutcome third = engine.handle(HarnessInput.of(session, "third"));
        assertEquals(InputOutcome.Kind.TASK, third.getKind());
        third.discard();
    }

    // ---------- 2. 占位跟着请求走 ----------

    @Test
    void ticketIsReleasedOnlyAfterRunIsRegistered() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());

        // 订阅调度到别的线程之前：仍判忙（Web 端曾在这段空档里放进第二条流）
        assertEquals(InputOutcome.Kind.REJECTED, engine.handle(HarnessInput.of(session, "concurrent")).getKind());

        AtomicBoolean busyAtRelease = new AtomicBoolean();
        AtomicBoolean admittingAtRelease = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        outcome.getRequest().stream()
                // 位于请求自己的释放之后：此刻必须「已登记运行流、已释放占位」，两者之间没有空档
                .doOnSubscribe(s -> {
                    busyAtRelease.set(engine.status(session).isBusy());
                    admittingAtRelease.set(engine.status(session).isAdmitting());
                })
                .doFinally(s -> done.countDown())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(e -> { }, e -> { });

        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(busyAtRelease.get(), "释放占位时运行流必须已登记");
        assertFalse(admittingAtRelease.get(), "订阅后占位已释放");
        assertFalse(engine.status(session).isAdmitting(), "流结束后不残留占位");
    }

    @Test
    void ticketExpiresWhenTaskIsNeverStarted() throws Exception {
        HarnessEngine engine = builder().admissionTimeoutMs(150L).build();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "never started"));
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertTrue(engine.status(session).isAdmitting());

        Thread.sleep(300);
        assertFalse(engine.status(session).isAdmitting(), "超时的占位不再算占用");
        InputOutcome next = engine.handle(HarnessInput.of(session, "next"));
        assertEquals(InputOutcome.Kind.TASK, next.getKind());
        next.discard();
        outcome.discard(); // 迟到的旧凭据释放不得误清新占位（此处 next 已释放，仅验证不抛）
    }

    @Test
    void lateReleaseOfStaleTicketDoesNotClearNewOne() throws Exception {
        HarnessEngine engine = builder().admissionTimeoutMs(100L).build();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome stale = engine.handle(HarnessInput.of(session, "stale"));
        Thread.sleep(200);
        InputOutcome fresh = engine.handle(HarnessInput.of(session, "fresh"));
        assertEquals(InputOutcome.Kind.TASK, fresh.getKind());

        stale.discard();
        assertTrue(engine.status(session).isAdmitting(), "旧凭据只清自己，不清新占位");
        fresh.discard();
        assertFalse(engine.status(session).isAdmitting());
    }

    @Test
    void interruptClearsTicketOfUnstartedTask() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        assertTrue(engine.status(session).isAdmitting());

        engine.interrupt(session, InterruptMode.INTERRUPT);
        assertFalse(engine.status(session).isAdmitting(), "用户中断兜底清除占位（宿主看到已取消就不再订阅）");
        outcome.discard();
    }

    // ---------- 3. HITL ----------

    @Test
    void pendingHitlRejectsPlainInputButNotDecisionsOrBusyCommands() {
        HarnessEngine engine = engine();
        engine.getCommandRegistry().register(new InterruptCommand());
        AgentSession session = InMemoryAgentSession.of();
        HITLTask task = new HITLTask("call-1", "bash", Collections.singletonMap("command", "ls"), "确认执行");
        session.getContext().put(HITL.PENDING_TASKS, Collections.singletonList(task));
        session.getContext().put(HITL.LAST_INTERVENED, task);

        assertTrue(engine.status(session).hasPendingHitl(), "前置：有待审批卡");

        InputOutcome plain = engine.handle(HarnessInput.of(session, "新的问题"));
        assertEquals(InputOutcome.Kind.REJECTED, plain.getKind());
        assertEquals(InputOutcome.RejectReason.HITL_PENDING, plain.getRejectReason());
        assertTrue(plain.getRejectDetail().contains("bash"));

        InputOutcome decision = engine.handle(HarnessInput.of(session, null).hitl(HITLAction.APPROVE, "call-1"));
        assertNotEquals(InputOutcome.Kind.REJECTED, decision.getKind(), "审批决策本身不被拦");
        decision.discard();
        assertFalse(engine.status(session).isAdmitting());
    }

    // ---------- 4. 忙时可执行命令 ----------

    @Test
    void busyInterruptRunsImmediatelyWhileOtherCommandsAreDeferred() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        engine.getCommandRegistry().register(new InterruptCommand());
        AtomicInteger executed = new AtomicInteger();
        engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public String description() {
                return "plain";
            }

            @Override
            public String[] examples() {
                return new String[0];
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
                executed.incrementAndGet();
            }
        });
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        InputOutcome plain = engine.handle(HarnessInput.of(session, "/plain"));
        assertEquals(InputOutcome.Kind.DEFERRED, plain.getKind(), "普通命令忙时排队，与 Web 口径一致");
        assertEquals(0, executed.get());

        InputOutcome interrupt = engine.handle(HarnessInput.of(session, "/interrupt"));
        assertEquals(InputOutcome.Kind.REPLY, interrupt.getKind(), "/interrupt 忙时立即执行");
        assertTrue(interrupt.isBesideRunning(), "在运行中的任务旁边执行：宿主不得改写回复路由");
        assertFalse(engine.status(session).isAdmitting());
    }

    // ---------- 6. 程序轮次 ----------

    @Test
    void busyProgramInputIsRejectedAndNotQueued() {
        HarnessEngine engine = engine();
        StubBacklog backlog = new StubBacklog();
        engine.addExtension(backlog);
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        InputOutcome outcome = engine.handle(HarnessInput.ofProgram(session, "goal 的一轮", "goal"));
        assertEquals(InputOutcome.Kind.REJECTED, outcome.getKind());
        assertEquals(InputOutcome.RejectReason.BUSY, outcome.getRejectReason());
        assertTrue(backlog.offered.isEmpty(), "程序轮次不进积压");
        assertTrue(HarnessInput.ofProgram(session, "x", "loop").isProgram());
    }

    // ---------- 7. 命令内部重入 ----------

    @Test
    void commandTaskReentryInheritsOuterTicket() {
        HarnessEngine engine = engine();
        engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return "again";
            }

            @Override
            public String description() {
                return "again";
            }

            @Override
            public String[] examples() {
                return new String[0];
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
                ctx.requestAgentTask("再跑一轮", null);
            }
        });
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/again"));
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind(), "命令内部再次 handle 不得被外层占位拒掉");
        assertTrue(outcome.isCommand());
        assertTrue(engine.status(session).isAdmitting(), "命令触发的任务同样持有占位直到开流");
        outcome.discard();
        assertFalse(engine.status(session).isAdmitting());
    }

    // ---------- 8. 非 TASK 结局 ----------

    @Test
    void nonTaskOutcomesReleaseBeforeReturning() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        engine.getCommandRegistry().register(new InterruptCommand());

        assertEquals(InputOutcome.Kind.IGNORED, engine.handle(HarnessInput.of(session, "")).getKind());
        assertFalse(engine.status(session).isAdmitting());

        assertEquals(InputOutcome.Kind.REPLY, engine.handle(HarnessInput.of(session, "/interrupt")).getKind());
        assertFalse(engine.status(session).isAdmitting());
        assertNull(session.attrs().get(HarnessAttrs.INPUT_ADMITTING));
    }

    @Test
    void sessionlessInputIsNeverGated() {
        HarnessEngine engine = engine();
        InputOutcome a = engine.handle(HarnessInput.of(null, "a"));
        InputOutcome b = engine.handle(HarnessInput.of(null, "b"));
        assertEquals(InputOutcome.Kind.TASK, a.getKind());
        assertEquals(InputOutcome.Kind.TASK, b.getKind(), "无会话（如一次性摘要）不判忙");
    }

    @Test
    void ticketReleaseIsIdempotentAcrossDiscardAndStream() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        AtomicReference<Throwable> err = new AtomicReference<>();
        outcome.discard();
        outcome.discard();
        assertNull(err.get());
        assertFalse(engine.status(session).isAdmitting());
    }

    /** 对外判定与受理判定同一份口径：terminalOnly 的忙时命令仅终端输入算（此前对外入口一律排除） */
    @Test
    void runnableWhenBusyMatchesAdmissionForTerminalOnlyCommand() {
        HarnessEngine engine = engine();
        engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return "tty";
            }

            @Override
            public String description() {
                return "tty";
            }

            @Override
            public String[] examples() {
                return new String[0];
            }

            @Override
            public boolean terminalOnly() {
                return true;
            }

            @Override
            public boolean runnableWhenBusy() {
                return true;
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
            }
        });
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        HarnessInput web = HarnessInput.of(session, "/tty");
        HarnessInput tty = HarnessInput.of(session, "/tty").terminal(true);
        assertFalse(engine.isRunnableWhenBusy(web), "非终端来源：terminalOnly 命令不算忙时可执行");
        assertTrue(engine.isRunnableWhenBusy(tty), "终端来源：与受理判定一致，放行");
        assertFalse(engine.isRunnableWhenBusy(HarnessInput.of(session, "/tty").commandAware(false)));
        assertFalse(engine.isRunnableWhenBusy(null));

        InputOutcome ttyOutcome = engine.handle(HarnessInput.of(session, "/tty").terminal(true));
        assertTrue(ttyOutcome.isBesideRunning(), "受理判定同口径：终端来源的 terminalOnly 忙时命令旁路执行");
        ttyOutcome.discard();
    }
}
