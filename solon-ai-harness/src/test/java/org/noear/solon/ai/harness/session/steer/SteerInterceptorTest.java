package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.harness.input.InputOrigin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
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
    private SteerInterceptor<Object> interceptor;

    @BeforeEach
    public void setUp() {
        session = InMemoryAgentSession.of();
        trace = new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }
        };
        // webGate/wsContext 传 null：事件推送与展示记录为 no-op，聚焦注入逻辑
        interceptor = new SteerInterceptor<>(null, null);
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

        SteerInterceptor.SteerResult result = SteerInterceptor.steer(session, null, "once", "补充要求",
                InputOrigin.channel("Feishu"), () -> {
                    interceptor.onReasonStart(trace, new StringBuilder()); // 模拟复查前推理线程取走消息
                    return false;
                });

        assertEquals(SteerInterceptor.SteerStatus.STEERED, result.getStatus());
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

        SteerInterceptor.SteerResult result = SteerInterceptor.steer(session, null, "once", "补充要求",
                InputOrigin.channel("Feishu"), () -> {
                    interceptor.onReasonStart(trace, new StringBuilder());
                    interceptor.onAgentEnd(trace);
                    return false;
                });

        assertEquals(SteerInterceptor.SteerStatus.STEERED, result.getStatus());
        assertEquals(1, trace.getWorkingMemory().getMessages().size());
        assertNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID));
    }

    @Test
    @DisplayName("入邮箱后复查变为空闲：未被消费的插话可回滚并降级")
    public void unconsumedBeforeBusyRecheck_rollsBack() {
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());

        SteerInterceptor.SteerResult result = SteerInterceptor.steer(session, null, "pending", "补充要求",
                InputOrigin.channel("Feishu"), () -> false);

        assertEquals(SteerInterceptor.SteerStatus.NOT_RUNNING, result.getStatus());
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
}
