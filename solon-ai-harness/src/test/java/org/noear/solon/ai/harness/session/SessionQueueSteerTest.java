package org.noear.solon.ai.harness.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.ai.harness.session.queue.SessionQueueDrainer;
import org.noear.solon.ai.harness.session.queue.SessionQueueItem;
import org.noear.solon.ai.harness.session.steer.SteerMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级排队与插话载体，以及队列取用器的认领/释放语义。
 */
class SessionQueueSteerTest {

    private static AgentSession session() {
        return InMemoryAgentSession.of("q1");
    }

    // ========== SteerMessage ==========

    @Test
    void steerMessageDefaultsToNoOrigin() {
        SteerMessage m = new SteerMessage("1", "稍等，换个方向");
        assertEquals("1", m.getId());
        assertEquals("稍等，换个方向", m.getText());
        assertSame(InputOrigin.NONE, m.getOrigin());
    }

    @Test
    void steerMessageAcceptsChannelOnly() {
        SteerMessage m = new SteerMessage("1", "t", "feishu");
        assertEquals("feishu", m.getOrigin().getChannel());
        assertTrue(m.getOrigin().getReplyTarget() == null);
    }

    @Test
    void steerMessageAcceptsFullIdentity() {
        SteerMessage m = new SteerMessage("1", "t", "feishu", "u1", "reply-1", "msg-1");
        InputOrigin o = m.getOrigin();
        assertEquals("feishu", o.getChannel());
        assertEquals("u1", o.getSenderId());
        assertEquals("reply-1", o.getReplyTarget());
        assertEquals("msg-1", o.getMessageId());
    }

    @Test
    void steerMessageNormalizesNullOrigin() {
        SteerMessage m = new SteerMessage("1", "t", (InputOrigin) null);
        assertSame(InputOrigin.NONE, m.getOrigin());
    }

    // ========== SessionQueueDrainer ==========

    /** 入队统一走 enqueue（它负责归一化、上限与去重），不直接构造 item。 */
    private static void offer(AgentSession session, String text) {
        SessionQueue.enqueue(session, text, InputOrigin.NONE);
    }

    @Test
    void drainOneReturnsFalseWhenEmpty() {
        AgentSession s = session();
        assertFalse(SessionQueueDrainer.drainOne(s, i -> { }));
    }

    @Test
    void drainOneReturnsFalseOnNullArgs() {
        assertFalse(SessionQueueDrainer.drainOne(null, i -> { }));
        assertFalse(SessionQueueDrainer.drainOne(session(), null));
    }

    @Test
    void drainOnePollsAndRuns() throws Exception {
        AgentSession s = session();
        offer(s, "第一条");

        List<String> seen = new ArrayList<>();
        boolean drained = SessionQueueDrainer.drainOne(s, i -> seen.add(i.getText()));

        assertTrue(drained);
        assertEquals(1, seen.size());
        assertEquals("第一条", seen.get(0));
    }

    @Test
    void drainOneStopsAfterOneItem() {
        AgentSession s = session();
        offer(s, "第一条");
        offer(s, "第二条");

        List<String> seen = new ArrayList<>();
        SessionQueueDrainer.drainOne(s, i -> seen.add(i.getText()));

        assertEquals(1, seen.size(), "一次只取一项，剩余留给下一轮");
        // 成功返回即确认（acknowledgeOnReturn=true），剩余一项留在队列
        assertEquals(1, SessionQueue.size(s));
        assertEquals("第二条", SessionQueue.poll(s).getText());
    }

    @Test
    void drainOneReleasesClaimWhenRunnerThrows() {
        AgentSession s = session();
        offer(s, "会失败");

        try {
            SessionQueueDrainer.drainOne(s, i -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // 运行者异常向外传播（由调用方决定吞或抛）
        }

        // 认领必须已释放，否则队列会永久卡住（可再次认领即证明已释放）
        assertTrue(SessionQueue.tryClaim(s));
        SessionQueue.releaseClaim(s);
    }

    @Test
    void drainOneSkipsWhenAlreadyClaimed() {
        AgentSession s = session();
        offer(s, "x");
        assertTrue(SessionQueue.tryClaim(s));

        List<String> seen = new ArrayList<>();
        assertFalse(SessionQueueDrainer.drainOne(s, i -> seen.add(i.getText())));
        assertTrue(seen.isEmpty());
    }

    // ========== SessionQueue 基础语义 ==========

    @Test
    void queueIsFifo() {
        AgentSession s = session();
        offer(s, "a");
        offer(s, "b");

        SessionQueueItem first = SessionQueue.poll(s);
        assertEquals("a", first.getText());
        // poll 会把条目置为 in-flight；未确认前不再取下一项（严格串行执行）
        assertNull(SessionQueue.poll(s));

        SessionQueue.acknowledge(s, first.getId());
        assertEquals("b", SessionQueue.poll(s).getText());
    }

    @Test
    void pollBlocksUntilInFlightAcknowledged() {
        AgentSession s = session();
        offer(s, "a");
        offer(s, "b");

        SessionQueueItem first = SessionQueue.poll(s);
        assertEquals("a", first.getText());
        assertEquals("RUNNING", first.getStatus());

        SessionQueue.acknowledge(s, first.getId());
        // 确认后队列仍有剩余项
        assertTrue(SessionQueue.hasItems(s));
    }

    @Test
    void queueSizeAndClear() {
        AgentSession s = session();
        offer(s, "a");
        offer(s, "b");
        assertEquals(2, SessionQueue.size(s));

        SessionQueue.cancelPending(s);
        assertFalse(SessionQueue.hasItems(s));
    }

    @Test
    void queueIsPerSession() {
        AgentSession a = InMemoryAgentSession.of("qa");
        AgentSession b = InMemoryAgentSession.of("qb");
        offer(a, "只给 a");

        assertFalse(SessionQueue.hasItems(b));
        assertEquals(1, SessionQueue.size(a));
    }

    @Test
    void enqueueNormalizesAndRejectsEmpty() {
        AgentSession s = session();
        assertEquals(-1, SessionQueue.enqueue(s, "   ", InputOrigin.NONE));
        assertEquals(-1, SessionQueue.enqueue(s, null, InputOrigin.NONE));

        assertEquals(1, SessionQueue.enqueue(s, "  hello  ", InputOrigin.NONE));
        assertEquals("hello", SessionQueue.poll(s).getText());
    }

    @Test
    void enqueueRejectsDuplicateId() {
        AgentSession s = session();
        assertEquals(1, SessionQueue.enqueue(s, "a", InputOrigin.NONE, "id-1", null, null, null, null, false));
        assertEquals(-1, SessionQueue.enqueue(s, "b", InputOrigin.NONE, "id-1", null, null, null, null, false));
    }
}
