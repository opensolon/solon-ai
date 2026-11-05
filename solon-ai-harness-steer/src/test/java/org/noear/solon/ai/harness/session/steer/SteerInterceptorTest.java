package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.queue.QueueExtension;
import org.noear.solon.ai.harness.session.queue.QueueHelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SteerInterceptor 单元测试：开轮/收轮交接、守卫体系、注入语义、残留兜底。
 */
public class SteerInterceptorTest {

    private AgentSession session;
    private ReActTrace trace;
    private SteerInterceptor interceptor;
    /** 捕获的事件（ReActTrace#pushAgentEvent 被覆写为记录，不依赖流式 sink） */
    private final List<AgentEvent> pushed = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        session = InMemoryAgentSession.of();
        pushed.clear();
        trace = newTrace();
        // 转排队目标经核心能力接口 InputBacklog 注入（生产中由 SteerExtension 从引擎现取）
        final QueueExtension backlog = new QueueExtension();
        interceptor = new SteerInterceptor(() -> backlog);
    }

    private ReActTrace newTrace() {
        return new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }

            @Override
            public void pushAgentEvent(AgentEvent event) {
                pushed.add(event);
            }
        };
    }

    private <T> List<T> eventsOf(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (AgentEvent event : pushed) {
            if (type.isInstance(event)) {
                result.add(type.cast(event));
            }
        }
        return result;
    }

    /** 开轮并把若干插话直接放进本轮邮箱（id 为 s0, s1...） */
    private SteerState startWithInbox(ReActTrace t, String... texts) {
        interceptor.onAgentStart(t);
        SteerState state = SteerState.of(session);
        synchronized (state) {
            for (int i = 0; i < texts.length; i++) {
                state.inbox.addLast(new SteerMessage("s" + i, texts[i]));
            }
        }
        return state;
    }

    private SteerResult steerNow(String steerId, String text) {
        return SteerHelper.steer(session, null, steerId, text, InputOrigin.channel("Feishu"), () -> true,
                SteerLimits.DEFAULT, false);
    }

    private static AssistantMessage toolCallMessage() {
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [{" +
                "    \"id\": \"c1\"," +
                "    \"name\": \"bash\"," +
                "    \"arguments\": {}" +
                "  }]" +
                "}";
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    private static String hugeText() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < QueueHelper.MAX_TEXT_LENGTH + 10; i++) {
            huge.append('x');
        }
        return huge.toString();
    }

    // ==================== 开轮 ====================

    @Test
    @DisplayName("onAgentStart 开轮：建立轮次标识，投递从此可被接受")
    public void agentStart_opensTurn() {
        assertEquals(SteerStatus.NOT_RUNNING, steerNow(null, "太早").getStatus(), "未开轮时不接受投递");

        interceptor.onAgentStart(trace);

        assertEquals(trace.getRunId(), SteerState.of(session).runId());
        assertTrue(steerNow(null, "补充").isSteered());
    }

    @Test
    @DisplayName("中断后新任务：上一轮残留转排队、推 RUN_SUPERSEDED，绝不注入新任务")
    public void interruptedRunLeftovers_queuedNotInjectedIntoNextRun() {
        interceptor.onAgentStart(trace);
        assertTrue(steerNow("a", "旧插话").isSteered());

        // 模拟 interrupt：不调用 onAgentEnd；新 prompt 产生新 runId
        ReActTrace next = newTrace();
        assertNotEquals(trace.getRunId(), next.getRunId());
        interceptor.onAgentStart(next);

        next.nextTurn();
        next.nextTurn();
        interceptor.onReasonStart(next, new StringBuilder());
        assertTrue(next.getWorkingMemory().isEmpty(), "旧插话不得注入新任务");
        assertTrue(eventsOf(SteerAppliedEvent.class).isEmpty());

        assertEquals(1, QueueHelper.pendingSize(session), "旧插话必须转入排队");
        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size());
        assertEquals(SteerDroppedEvent.Reason.RUN_SUPERSEDED, dropped.get(0).getReason());
        assertEquals("a", dropped.get(0).getQueued().get(0).getId());
        assertEquals(next.getRunId(), dropped.get(0).getRunId(), "事件属于新 run");
        assertEquals(next.getRunId(), SteerState.of(session).runId());
    }

    @Test
    @DisplayName("同 runId 续跑（/continue）：属于同一逻辑任务，邮箱保留并在续跑中注入")
    public void continueWithSameRunId_keepsInbox() {
        startWithInbox(trace, "补充");

        interceptor.onAgentStart(trace); // 续跑沿用同一 trace / runId

        assertEquals(0, QueueHelper.pendingSize(session));
        assertTrue(eventsOf(SteerDroppedEvent.class).isEmpty());
        trace.nextTurn();
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        assertEquals(1, trace.getWorkingMemory().getMessages().size());
    }

    @Test
    @DisplayName("防御性开轮：未经 onAgentStart（中途启用）时 onReasonStart 自行建立轮次")
    public void reasonStart_opensTurnDefensively() {
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        assertEquals(trace.getRunId(), SteerState.of(session).runId());
    }

    // ==================== 守卫与注入 ====================

    @Test
    @DisplayName("守卫1：首轮（turnCount=1）不注入，邮箱保留到下一轮")
    public void firstTurn_notInjected() {
        SteerState state = startWithInbox(trace, "改用方案B");
        trace.nextTurn(); // 首轮

        StringBuilder sp = new StringBuilder("base");
        interceptor.onReasonStart(trace, sp);

        assertTrue(trace.getWorkingMemory().isEmpty(), "首轮不应注入工作记忆");
        assertEquals(1, state.size(), "邮箱应保留");
        assertEquals("base", sp.toString(), "systemPrompt 不应追加说明");
    }

    @Test
    @DisplayName("非首轮：注入工作记忆（带前缀+metadata）、不污染 systemPrompt、清空邮箱")
    public void laterTurn_injected() {
        SteerState state = startWithInbox(trace, "改用方案B", "注意性能");
        trace.nextTurn();
        trace.nextTurn(); // 第二轮

        StringBuilder sp = new StringBuilder("base");
        interceptor.onReasonStart(trace, sp);

        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).getContent().startsWith(SteerMessages.PREFIX));
        assertTrue(messages.get(0).getContent().contains("改用方案B"));
        assertEquals("steer", messages.get(0).getMetadata().get("source"));
        assertEquals("s0", messages.get(0).getMetadata().get(SteerMessages.META_STEER_ID));
        assertEquals("user", messages.get(0).getRole().name().toLowerCase());
        assertEquals("改用方案B", SteerMessages.textOf(messages.get(0)), "公开协议可还原原话");
        assertEquals("base", sp.toString(), "systemPrompt 不应被修改");
        assertEquals(0, state.size(), "注入后邮箱应清空");
    }

    @Test
    @DisplayName("守卫3：工作记忆尾部 tool_calls 未闭合（HITL 挂起窄窗）跳过注入")
    public void openToolCalls_skipped() {
        SteerState state = startWithInbox(trace, "纠偏");
        trace.getWorkingMemory().addMessage(toolCallMessage());
        trace.nextTurn();
        trace.nextTurn();

        interceptor.onReasonStart(trace, new StringBuilder());

        assertEquals(1, trace.getWorkingMemory().getMessages().size(), "不应注入");
        assertEquals(1, state.size(), "邮箱应保留");
    }

    @Test
    @DisplayName("守卫3豁免：tool_calls 已有结果消息（正常 action 收口后）正常注入")
    public void closedToolCalls_injected() {
        startWithInbox(trace, "继续");
        Prompt wm = trace.getWorkingMemory();
        wm.addMessage(toolCallMessage());
        wm.addMessage(ChatMessage.ofTool("done", "bash", "c1"));
        trace.nextTurn();
        trace.nextTurn();

        interceptor.onReasonStart(trace, new StringBuilder());

        assertEquals(3, wm.getMessages().size(), "tool+result 之外应新增 1 条注入");
        assertTrue(wm.getMessages().get(2).getContent().contains("继续"));
    }

    @Test
    @DisplayName("空邮箱零行为：不注入、不追加 systemPrompt")
    public void emptyBox_noop() {
        interceptor.onAgentStart(trace);
        trace.nextTurn();
        trace.nextTurn();
        StringBuilder sp = new StringBuilder("x");
        interceptor.onReasonStart(trace, sp);
        assertEquals("x", sp.toString());
        assertTrue(trace.getWorkingMemory().isEmpty());
        assertTrue(pushed.isEmpty());
    }

    // ==================== 收轮 ====================

    @Test
    @DisplayName("onAgentEnd：残留清出、轮次标识清除")
    public void agentEnd_droppedAndCleaned() {
        SteerState state = startWithInbox(trace, "未消费1", "未消费2");

        interceptor.onAgentEnd(trace);

        assertEquals(0, state.size(), "残留应被清出");
        assertNull(state.runId(), "轮次标识应清除");
    }

    @Test
    @DisplayName("onAgentEnd 无状态 / 无残留：静默，不抛异常、不推事件")
    public void agentEnd_noResidue_clean() {
        assertDoesNotThrow(() -> interceptor.onAgentEnd(trace));
        interceptor.onAgentStart(trace);
        interceptor.onAgentEnd(trace);
        assertTrue(pushed.isEmpty());
    }

    @Test
    @DisplayName("旧任务迟到的 onAgentEnd：不能清除新任务的轮次和邮箱")
    public void oldAgentEnd_doesNotClearNewRunState() {
        ReActTrace next = newTrace();
        SteerState state = startWithInbox(next, "新任务插话");

        interceptor.onAgentEnd(trace);

        assertEquals(next.getRunId(), state.runId());
        assertEquals(1, state.size(), "旧任务结束不得丢弃新任务插话");
    }

    // ==================== 投递复查 ====================

    @Test
    @DisplayName("按稳定 ID 撤销：只移除目标插话，重复文本不误删")
    public void cancelById_removesOnlyTarget() {
        SteerState state = startWithInbox(trace, "相同文本", "相同文本");

        assertTrue(SteerHelper.cancel(state, trace.getRunId(), "s0").isCanceled());
        assertEquals(1, state.size());
        assertEquals("s1", state.snapshot().get(0).getId());
        assertEquals(SteerCancelStatus.NOT_PENDING, SteerHelper.cancel(state, trace.getRunId(), "s0").getStatus(),
                "重复取消不得误删另一条同文本插话");
    }

    @Test
    @DisplayName("并发入队：容量上限是硬约束")
    public void concurrentOffer_neverExceedsCapacity() throws Exception {
        interceptor.onAgentStart(trace);
        int accepted = concurrentSteer(24, i -> "s" + i);
        assertEquals(SteerHelper.MAX_BOX_SIZE, accepted);
        assertEquals(SteerHelper.MAX_BOX_SIZE, SteerState.of(session).size());
    }

    @Test
    @DisplayName("并发入队：相同 steerId 最多接受一次")
    public void concurrentOffer_duplicateIdAcceptedOnce() throws Exception {
        interceptor.onAgentStart(trace);
        int accepted = concurrentSteer(16, i -> "same");
        assertEquals(1, accepted);
        assertEquals(1, SteerState.of(session).size());
    }

    private int concurrentSteer(int n, java.util.function.IntFunction<String> idOf) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger offered = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (steerNow(idOf.apply(index), "text").isSteered()) {
                        offered.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        return offered.get();
    }

    @Test
    @DisplayName("满邮箱中的重复 ID：优先返回重复，不能误报容量已满")
    public void fullBox_duplicateIdStillReportedAsDuplicate() {
        interceptor.onAgentStart(trace);
        for (int i = 0; i < SteerHelper.MAX_BOX_SIZE; i++) {
            assertTrue(steerNow("s" + i, "text").isSteered());
        }

        assertEquals(SteerStatus.DUPLICATE_STEER_ID, steerNow("s0", "retry").getStatus());
        assertEquals(SteerStatus.BOX_FULL, steerNow("new", "retry").getStatus());
        assertEquals(SteerHelper.MAX_BOX_SIZE, SteerState.of(session).size());
    }

    @Test
    @DisplayName("入邮箱后复查变为空闲：已被推理消费的插话仍返回 STEERED，不可降级排队")
    public void consumedBeforeBusyRecheck_staysSteered() {
        interceptor.onAgentStart(trace);
        trace.nextTurn();
        trace.nextTurn();

        SteerResult result = SteerHelper.steer(session, null, "once", "补充要求",
                InputOrigin.channel("Feishu"), () -> {
                    interceptor.onReasonStart(trace, new StringBuilder()); // 模拟复查前推理线程取走消息
                    return false;
                }, SteerLimits.DEFAULT, false);

        assertEquals(SteerStatus.STEERED, result.getStatus());
        assertEquals(1, trace.getWorkingMemory().getMessages().size());
        assertTrue(trace.getWorkingMemory().getMessages().get(0).getContent().contains("补充要求"));
    }

    @Test
    @DisplayName("复查前任务已收轮（残留已转排队）：仍返回 STEERED，不得导致重复排队")
    public void endedBeforeRecheck_staysSteered() {
        interceptor.onAgentStart(trace);

        SteerResult result = SteerHelper.steer(session, null, "once", "补充要求",
                InputOrigin.channel("Feishu"), () -> {
                    interceptor.onAgentEnd(trace);
                    return false;
                }, SteerLimits.DEFAULT, false);

        assertEquals(SteerStatus.STEERED, result.getStatus());
        assertEquals(1, QueueHelper.pendingSize(session), "已由收轮转排队，调用方不得再降级");
        assertNull(SteerState.of(session).runId());
    }

    @Test
    @DisplayName("入邮箱后复查变为空闲：未被消费的插话回滚为 NOT_RUNNING")
    public void unconsumedBeforeBusyRecheck_rollsBack() {
        interceptor.onAgentStart(trace);

        SteerResult result = SteerHelper.steer(session, null, "pending", "补充要求",
                InputOrigin.channel("Feishu"), () -> false, SteerLimits.DEFAULT, false);

        assertEquals(SteerStatus.NOT_RUNNING, result.getStatus());
        assertEquals(0, SteerState.of(session).size());
    }

    @Test
    @DisplayName("复查前轮次已切换（新任务开轮）：本条已被交接走，仍返回 STEERED")
    public void runSwitchedBeforeRecheck_staysSteered() {
        interceptor.onAgentStart(trace);
        ReActTrace next = newTrace();

        SteerResult result = SteerHelper.steer(session, null, "x", "补充",
                InputOrigin.NONE, () -> {
                    interceptor.onAgentStart(next);
                    return true;
                }, SteerLimits.DEFAULT, false);

        assertEquals(SteerStatus.STEERED, result.getStatus());
        assertEquals(1, QueueHelper.pendingSize(session), "随新任务开轮转入排队，恰好一次");
        assertEquals(0, SteerState.of(session).size());
    }

    // ==================== 输出事件 ====================

    @Test
    @DisplayName("applied: 非首轮注入时必须推出 SteerAppliedEvent（runId 与 items 原样）")
    public void applied_eventPushed() {
        startWithInbox(trace, "改用方案B");
        trace.nextTurn();
        trace.nextTurn();

        interceptor.onReasonStart(trace, new StringBuilder());

        List<SteerAppliedEvent> applied = eventsOf(SteerAppliedEvent.class);
        assertEquals(1, applied.size(), "注入生效后必须发出一次 applied 事件");
        assertEquals(trace.getRunId(), applied.get(0).getRunId());
        assertEquals(1, applied.get(0).getItems().size());
        assertEquals("改用方案B", applied.get(0).getItems().get(0).getText());
        assertSame(session, applied.get(0).getSession(), "事件自带 session，宿主据此定位投递目标");
    }

    @Test
    @DisplayName("applied: 守卫跳过时不推事件（首轮 / tool_calls 未闭合）")
    public void applied_notPushedWhenGuardSkips() {
        startWithInbox(trace, "改用方案B");
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        assertTrue(eventsOf(SteerAppliedEvent.class).isEmpty(), "首轮不得推出 applied");

        trace.getWorkingMemory().addMessage(toolCallMessage());
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        assertTrue(eventsOf(SteerAppliedEvent.class).isEmpty(), "tool_calls 未闭合不得推出 applied");
    }

    @Test
    @DisplayName("dropped: 任务结束残留必须转入 session 队列，原因 RUN_ENDED")
    public void dropped_enqueuesIntoSessionQueue() {
        startWithInbox(trace, "未消费1", "未消费2");

        interceptor.onAgentEnd(trace);

        assertEquals(2, QueueHelper.pendingSize(session), "残留必须转入 session 队列，绝不静默丢弃");

        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size(), "转排队后必须推出一次 dropped 事件");
        assertEquals(2, dropped.get(0).getQueued().size());
        assertFalse(dropped.get(0).hasFailed());
        assertEquals(SteerDroppedEvent.Reason.RUN_ENDED, dropped.get(0).getReason());
        assertEquals(trace.getRunId(), dropped.get(0).getRunId());
    }

    @Test
    @DisplayName("dropped: 无事件消费者（非流式）时队列仍必须增长")
    public void dropped_noSubscriberStillQueues() {
        ReActTrace silent = new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }
        };
        startWithInbox(silent, "未消费");

        interceptor.onAgentEnd(silent);

        assertEquals(1, QueueHelper.pendingSize(session), "无订阅者也必须完成转排队");
    }

    @Test
    @DisplayName("dropped: 队列满时失败项暂存为 orphans 并标记 failed，pending 仍可见")
    public void dropped_queueFullParksAsOrphan() {
        for (int i = 0; i < QueueHelper.MAX_QUEUE_SIZE; i++) {
            assertTrue(QueueHelper.enqueue(session, "task " + i, InputOrigin.channel("WEB")) > 0);
        }
        SteerState state = startWithInbox(trace, "do not lose me");

        interceptor.onAgentEnd(trace);

        assertEquals(QueueHelper.MAX_QUEUE_SIZE, QueueHelper.pendingSize(session), "队列不得超上限");
        assertEquals(1, state.orphans.size(), "失败项必须暂存待重试");
        assertEquals(0, state.inbox.size(), "失败项不得回到邮箱（会被注入下一任务）");
        assertEquals("s0", state.snapshot().get(0).getId());

        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size());
        assertTrue(dropped.get(0).getQueued().isEmpty());
        assertTrue(dropped.get(0).hasFailed(), "失败项必须在事件中可辨识");
    }

    @Test
    @DisplayName("orphans 永不注入：下一任务开轮时只重试转排队")
    public void orphansAreNeverInjected_andRetriedOnNextOpen() {
        SteerState state = startWithInbox(trace, hugeText()); // 超长文本被队列拒绝 → 成为 orphan
        interceptor.onAgentEnd(trace);
        assertEquals(1, state.orphans.size());
        pushed.clear();

        ReActTrace next = newTrace();
        interceptor.onAgentStart(next);
        next.nextTurn();
        next.nextTurn();
        interceptor.onReasonStart(next, new StringBuilder());

        assertTrue(next.getWorkingMemory().isEmpty(), "orphan 不得注入新任务");
        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size(), "开轮时重试一次（仍失败则再次标记）");
        assertTrue(dropped.get(0).hasFailed());
        assertEquals(1, state.orphans.size(), "仍失败则继续暂存");
    }

    @Test
    @DisplayName("orphans 不随推理回合反复重试（避免每回合重复推失败事件）")
    public void orphansNotRetriedEveryReasonTurn() {
        SteerState state = startWithInbox(trace);
        state.park(java.util.Collections.singletonList(new SteerMessage("o1", hugeText())));
        for (int i = 0; i < 3; i++) {
            trace.nextTurn();
            interceptor.onReasonStart(trace, new StringBuilder());
        }
        assertTrue(eventsOf(SteerDroppedEvent.class).isEmpty());
    }

    @Test
    @DisplayName("orphans 与轮次无关：任务结束后仍可撤销")
    public void orphansCancelableAfterRunEnded() {
        SteerState state = startWithInbox(trace, hugeText());
        interceptor.onAgentEnd(trace);

        assertTrue(SteerHelper.cancel(state, null, "s0").isCanceled());
        assertEquals(0, state.size());
    }

    @Test
    @DisplayName("transferToBacklog: 空列表 / null 会话零行为，不抛异常")
    public void transferToBacklog_noop() {
        QueueExtension backlog = new QueueExtension();
        assertTrue(SteerHelper.transferToBacklog(backlog, null, new ArrayList<>()).isEmpty());
        assertTrue(SteerHelper.transferToBacklog(backlog, session, null).isEmpty());
        assertTrue(SteerHelper.transferToBacklog(backlog, session, new ArrayList<>()).isEmpty());
        assertEquals(0, QueueHelper.pendingSize(session));
    }

    @Test
    @DisplayName("无积压能力（未装 queue 插件）：残留全部按转排队失败暂存，并经事件告知")
    public void noBacklog_leftoversParkedAsFailed() {
        interceptor = new SteerInterceptor();
        startWithInbox(trace, "改用 PostgreSQL");

        interceptor.onAgentEnd(trace);

        assertEquals(0, QueueHelper.pendingSize(session), "没有积压能力时不得绕过接口写队列");
        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size());
        assertEquals(1, dropped.get(0).getFailed().size());
        assertEquals(1, SteerState.peek(session).size(), "失败项暂存为 orphans，可撤销");
    }

    @Test
    @DisplayName("积压停用：转排队被拒收（不再绕过 queue 开关）")
    public void disabledBacklog_rejectsTransfer() {
        final QueueExtension backlog = new QueueExtension();
        backlog.setEnabled(false);
        interceptor = new SteerInterceptor(() -> backlog);
        startWithInbox(trace, "改用 PostgreSQL");

        interceptor.onAgentEnd(trace);

        assertEquals(0, QueueHelper.pendingSize(session));
        assertEquals(1, eventsOf(SteerDroppedEvent.class).get(0).getFailed().size());
    }

    @Test
    @DisplayName("onAgentEnd: 入队失败不得冒泡（拦截器链遍历处没有 try）")
    public void enqueueFailure_doesNotBreakInterceptorChain() {
        startWithInbox(trace, hugeText());

        assertDoesNotThrow(() -> interceptor.onAgentEnd(trace), "入队失败不得沿拦截器链冒泡");

        assertEquals(0, QueueHelper.pendingSize(session), "超长文本不得入队");
        assertEquals(1, SteerState.of(session).size(), "失败项必须暂存待重试");
    }

    @Test
    @DisplayName("并发：投递与收轮交错，每条插话只有一种结局（注入 / 排队 / 被拒）")
    public void concurrentSteerAndClose_neverDuplicates() throws Exception {
        for (int round = 0; round < 50; round++) {
            session = InMemoryAgentSession.of();
            ReActTrace t = newTrace();
            interceptor.onAgentStart(t);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger steered = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    final int index = i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        if (steerNow("c" + index, "t" + index).isSteered()) {
                            steered.incrementAndGet();
                        }
                        return null;
                    }));
                }
                futures.add(pool.submit(() -> {
                    start.await();
                    interceptor.onAgentEnd(t);
                    return null;
                }));
                start.countDown();
                for (Future<?> f : futures) f.get();
            } finally {
                pool.shutdownNow();
            }

            // 收轮后还留在邮箱的（收轮早于投递时被拒，不会留）+ 已排队 == 被接受的
            int queued = QueueHelper.pendingSize(session);
            int left = SteerState.of(session).size();
            assertEquals(steered.get(), queued + left, "被接受的插话必须恰好一次落到排队或邮箱");
            assertEquals(0, left, "收轮后邮箱不得残留");
        }
    }
}
