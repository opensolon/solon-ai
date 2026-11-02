package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.queue.SessionQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SteerInterceptor 单元测试：守卫体系、方案 A 注入语义、onAgentEnd 兜底与清理。
 *
 * <p>覆盖：首轮不注入、非首轮注入（workingMemory + 邮箱清空 + runId 记录）、
 * tool_calls 未闭合跳过、任务结束残留转 dropped、正常清理。</p>
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
        trace = new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }

            @Override
            public void pushAgentEvent(AgentEvent event) {
                pushed.add(event);
            }
        };
        // 输出走事件：拦截器无依赖，直接无参构造
        interceptor = new SteerInterceptor();
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

    private ConcurrentLinkedQueue<SteerMessage> newBox(String... texts) {
        ConcurrentLinkedQueue<SteerMessage> box = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < texts.length; i++) {
            box.offer(new SteerMessage("s" + i, texts[i]));
        }
        session.attrs().put(SteerInterceptor.ATTR_STEER_BOX, box);
        return box;
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

    @Test
    @DisplayName("守卫1：首轮（turnCount=1）不注入，邮箱保留到下一轮")
    public void firstTurn_notInjected() {
        ConcurrentLinkedQueue<SteerMessage> box = newBox("改用方案B");
        trace.nextTurn(); // 首轮

        StringBuilder sp = new StringBuilder("base");
        interceptor.onReasonStart(trace, sp);

        assertTrue(trace.getWorkingMemory().isEmpty(), "首轮不应注入工作记忆");
        assertEquals(1, box.size(), "邮箱应保留");
        assertEquals("base", sp.toString(), "systemPrompt 不应追加说明");
        assertNotNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID), "runId 仍应被记录");
    }

    @Test
    @DisplayName("非首轮：注入工作记忆（带前缀+metadata）、不污染 systemPrompt、清空邮箱、记录 runId")
    public void laterTurn_injected() {
        ConcurrentLinkedQueue<SteerMessage> box = newBox("改用方案B", "注意性能");
        trace.nextTurn();
        trace.nextTurn(); // 第二轮

        StringBuilder sp = new StringBuilder("base");
        interceptor.onReasonStart(trace, sp);

        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).getContent().contains(SteerInterceptor.STEER_PREFIX));
        assertTrue(messages.get(0).getContent().contains("改用方案B"));
        assertEquals("steer", messages.get(0).getMetadata().get("source"));
        assertEquals("user", messages.get(0).getRole().name().toLowerCase());
        // 注入消息自带 STEER_PREFIX 已足够表意，不再追加 systemPrompt（避免重复提示）
        assertEquals("base", sp.toString(), "systemPrompt 不应被修改");
        assertTrue(box.isEmpty(), "注入后邮箱应清空");
        assertEquals(trace.getRunId(), session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID));
    }

    @Test
    @DisplayName("守卫3：工作记忆尾部 tool_calls 未闭合（HITL 挂起窄窗）跳过注入")
    public void openToolCalls_skipped() {
        ConcurrentLinkedQueue<SteerMessage> box = newBox("纠偏");
        trace.getWorkingMemory().addMessage(toolCallMessage());
        trace.nextTurn();
        trace.nextTurn();

        interceptor.onReasonStart(trace, new StringBuilder());

        assertEquals(1, trace.getWorkingMemory().getMessages().size(), "不应注入");
        assertEquals(1, box.size(), "邮箱应保留");
    }

    @Test
    @DisplayName("守卫3豁免：tool_calls 已有结果消息（正常 action 收口后）正常注入")
    public void closedToolCalls_injected() {
        newBox("继续");
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
        trace.nextTurn();
        trace.nextTurn();
        StringBuilder sp = new StringBuilder("x");
        interceptor.onReasonStart(trace, sp);
        assertEquals("x", sp.toString());
        assertTrue(trace.getWorkingMemory().isEmpty());
    }

    @Test
    @DisplayName("onAgentEnd 残留兜底：未消费文本清空邮箱并清理 attrs（dropped 事件由通知通道发出）")
    public void agentEnd_droppedAndCleaned() {
        ConcurrentLinkedQueue<SteerMessage> box = newBox("未消费1", "未消费2");
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, trace.getRunId());

        interceptor.onAgentEnd(trace);

        assertTrue(box.isEmpty(), "残留应被清出（交由前端转排队）");
        assertNull(session.attrs().get(SteerInterceptor.ATTR_STEER_BOX), "邮箱标记应移除");
        assertNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID), "runId 标记应移除");
    }

    @Test
    @DisplayName("onAgentEnd 无残留：静默清理，不抛异常")
    public void agentEnd_noResidue_clean() {
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, trace.getRunId());
        interceptor.onAgentEnd(trace);
        assertNull(session.attrs().get(SteerInterceptor.ATTR_STEER_BOX));
    }

    @Test
    @DisplayName("按稳定 ID 撤销：只移除目标插话，重复文本不误删")
    public void cancelById_removesOnlyTarget() {
        ConcurrentLinkedQueue<SteerMessage> box = newBox("相同文本", "相同文本");

        assertTrue(SteerInterceptor.cancel(box, "s0"));
        assertEquals(1, box.size());
        assertEquals("s1", box.peek().getId());
        assertFalse(SteerInterceptor.cancel(box, "s0"), "重复取消不得误删另一条同文本插话");
    }

    @Test
    @DisplayName("并发入队：容量上限是硬约束")
    public void concurrentOffer_neverExceedsCapacity() throws Exception {
        ConcurrentLinkedQueue<SteerMessage> box = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger offered = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (SteerInterceptor.offer(box, new SteerMessage("s" + index, "text"))
                            == SteerInterceptor.OfferResult.OFFERED) {
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

        assertEquals(SteerInterceptor.MAX_BOX_SIZE, offered.get());
        assertEquals(SteerInterceptor.MAX_BOX_SIZE, box.size());
    }

    @Test
    @DisplayName("并发入队：相同 steerId 最多接受一次")
    public void concurrentOffer_duplicateIdAcceptedOnce() throws Exception {
        ConcurrentLinkedQueue<SteerMessage> box = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger offered = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (SteerInterceptor.offer(box, new SteerMessage("same", "text"))
                            == SteerInterceptor.OfferResult.OFFERED) {
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

        assertEquals(1, offered.get());
        assertEquals(1, box.size());
    }

    @Test
    @DisplayName("满邮箱中的重复 ID：优先返回重复，不能误报容量已满")
    public void fullBox_duplicateIdStillReportedAsDuplicate() {
        ConcurrentLinkedQueue<SteerMessage> box = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < SteerInterceptor.MAX_BOX_SIZE; i++) {
            assertEquals(SteerInterceptor.OfferResult.OFFERED,
                    SteerInterceptor.offer(box, new SteerMessage("s" + i, "text")));
        }

        assertEquals(SteerInterceptor.OfferResult.DUPLICATE_ID,
                SteerInterceptor.offer(box, new SteerMessage("s0", "retry")));
        assertEquals(SteerInterceptor.MAX_BOX_SIZE, box.size());
    }

    @Test
    @DisplayName("入邮箱后复查变为空闲：已被推理消费的插话仍返回 STEERED，不可降级排队")
    public void consumedBeforeBusyRecheck_staysSteered() {
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder()); // 建立当前 runId
        trace.nextTurn();

        SteerResult result = SteerInterceptor.steer(session, null, "once", "补充要求",
                InputOrigin.channel("Feishu"), () -> {
                    interceptor.onReasonStart(trace, new StringBuilder()); // 模拟复查前推理线程取走消息
                    return false;
                });

        assertEquals(SteerStatus.STEERED, result.getStatus());
        assertEquals(1, trace.getWorkingMemory().getMessages().size());
        assertTrue(trace.getWorkingMemory().getMessages().get(0).getContent().contains("补充要求"));
        assertNull(SteerInterceptor.steerBox(session), "消费后空邮箱可被清理");
    }

    @Test
    @DisplayName("消费后任务结束：runId 已消失也不能返回 TURN_CHANGED 导致重复排队")
    public void consumedAndEndedBeforeRecheck_staysSteered() {
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        trace.nextTurn();

        SteerResult result = SteerInterceptor.steer(session, null, "once", "补充要求",
                InputOrigin.channel("Feishu"), () -> {
                    interceptor.onReasonStart(trace, new StringBuilder());
                    interceptor.onAgentEnd(trace);
                    return false;
                });

        assertEquals(SteerStatus.STEERED, result.getStatus());
        assertEquals(1, trace.getWorkingMemory().getMessages().size());
        assertNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID));
    }

    @Test
    @DisplayName("入邮箱后复查变为空闲：未被消费的插话可回滚并降级")
    public void unconsumedBeforeBusyRecheck_rollsBack() {
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());

        SteerResult result = SteerInterceptor.steer(session, null, "pending", "补充要求",
                InputOrigin.channel("Feishu"), () -> false);

        assertEquals(SteerStatus.NOT_RUNNING, result.getStatus());
        assertTrue(trace.getWorkingMemory().isEmpty());
        assertNull(SteerInterceptor.steerBox(session));
    }

    @Test
    @DisplayName("旧任务结束：不能清除新任务已写入的 runId 和邮箱")
    public void oldAgentEnd_doesNotClearNewRunState() {
        String newRunId = trace.getRunId() + "-new";
        ConcurrentLinkedQueue<SteerMessage> newBox = newBox("新任务插话");
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, newRunId);

        interceptor.onAgentEnd(trace);

        assertEquals(newRunId, session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID));
        assertSame(newBox, session.attrs().get(SteerInterceptor.ATTR_STEER_BOX));
        assertEquals(1, newBox.size(), "旧任务结束不得丢弃新任务插话");
    }


    // ==================== 输出事件行为（applied / dropped 走 AgentEvent） ====================

    @Test
    @DisplayName("applied: 非首轮注入时必须推出 SteerAppliedEvent（runId 与 items 原样）")
    public void applied_eventPushed() {
        newBox("改用方案B");
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
        newBox("改用方案B");
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        assertTrue(eventsOf(SteerAppliedEvent.class).isEmpty(), "首轮不得推出 applied");

        trace.getWorkingMemory().addMessage(toolCallMessage());
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());
        assertTrue(eventsOf(SteerAppliedEvent.class).isEmpty(), "tool_calls 未闭合不得推出 applied");
    }

    @Test
    @DisplayName("dropped: 任务结束残留必须转入 session 队列（不依赖订阅者）")
    public void dropped_enqueuesIntoSessionQueue() {
        ConcurrentLinkedQueue<SteerMessage> box = newBox("未消费1", "未消费2");
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, trace.getRunId());

        interceptor.onAgentEnd(trace);

        assertTrue(box.isEmpty(), "残留必须清出易失邮箱");
        assertEquals(2, SessionQueue.pendingSize(session), "残留必须转入 session 队列，绝不静默丢弃");

        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size(), "转排队后必须推出一次 dropped 事件");
        assertEquals(2, dropped.get(0).getQueued().size());
        assertFalse(dropped.get(0).hasFailed());
        assertEquals(trace.getRunId(), dropped.get(0).getRunId());
    }

    @Test
    @DisplayName("dropped: 无事件消费者（非流式）时队列仍必须增长")
    public void dropped_noSubscriberStillQueues() {
        // pushAgentEvent 在无 sink 时静默丢弃，事件不能承载业务动作——持久化必须在 harness 内完成
        ReActTrace silent = new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }
        };
        newBox("未消费");
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, silent.getRunId());

        interceptor.onAgentEnd(silent);

        assertEquals(1, SessionQueue.pendingSize(session), "无订阅者也必须完成转排队");
    }

    @Test
    @DisplayName("dropped: 队列满时失败项回填易失邮箱，并标记 failed")
    public void dropped_queueFullKeepsInMailbox() {
        for (int i = 0; i < SessionQueue.MAX_QUEUE_SIZE; i++) {
            assertTrue(SessionQueue.enqueue(session, "task " + i, InputOrigin.channel("WEB")) > 0);
        }
        newBox("do not lose me");
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, trace.getRunId());

        interceptor.onAgentEnd(trace);

        assertEquals(SessionQueue.MAX_QUEUE_SIZE, SessionQueue.pendingSize(session), "队列不得超上限");
        Queue<SteerMessage> refilled = SteerInterceptor.steerBox(session);
        assertNotNull(refilled, "失败项必须回填易失邮箱待下次重试");
        assertEquals("s0", refilled.peek().getId());

        List<SteerDroppedEvent> dropped = eventsOf(SteerDroppedEvent.class);
        assertEquals(1, dropped.size());
        assertTrue(dropped.get(0).getQueued().isEmpty());
        assertTrue(dropped.get(0).hasFailed(), "失败项必须在事件中可辨识");
    }

    @Test
    @DisplayName("enqueueIntoSessionQueue: 空邮箱 / null 会话零行为，不抛异常")
    public void enqueueIntoSessionQueue_noop() {
        assertTrue(SteerInterceptor.enqueueIntoSessionQueue(null, new ArrayList<>()).isEmpty());
        assertTrue(SteerInterceptor.enqueueIntoSessionQueue(session, null).isEmpty());
        assertTrue(SteerInterceptor.enqueueIntoSessionQueue(session, new ArrayList<>()).isEmpty());
        assertEquals(0, SessionQueue.pendingSize(session));
    }

    @Test
    @DisplayName("onAgentEnd: 入队失败不得冒泡（拦截器链遍历处没有 try）")
    public void enqueueFailure_doesNotBreakInterceptorChain() {
        // 超长文本会被 SessionQueue 拒绝（>MAX_TEXT_LENGTH），制造真实的入队失败路径
        ConcurrentLinkedQueue<SteerMessage> box = new ConcurrentLinkedQueue<>();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < SessionQueue.MAX_TEXT_LENGTH + 10; i++) {
            huge.append('x');
        }
        box.offer(new SteerMessage("s0", huge.toString()));
        session.attrs().put(SteerInterceptor.ATTR_STEER_BOX, box);
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, trace.getRunId());

        assertDoesNotThrow(() -> interceptor.onAgentEnd(trace), "入队失败不得沿拦截器链冒泡");

        assertEquals(0, SessionQueue.pendingSize(session), "超长文本不得入队");
        assertEquals(1, SteerInterceptor.pending(session).size(), "失败项必须回填易失邮箱待重试");
    }
}
