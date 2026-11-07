package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessResult;

import static org.junit.jupiter.api.Assertions.*;

/** 实例级队列上限：builder 可调，缺省与旧常量一致，非法值拒绝 */
class QueueLimitsTest {
    private static String repeat(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
    }

    @Test
    void defaultsMatchLegacyConstants() {
        QueueExtension q = new QueueExtension();
        assertEquals(QueueStore.MAX_QUEUE_SIZE, q.getMaxSize());
        assertEquals(QueueStore.MAX_TEXT_LENGTH, q.getMaxTextLength());
        AgentSession s = InMemoryAgentSession.of();
        for (int i = 0; i < 20; i++) assertTrue(q.enqueue(s, "m" + i).isOk());
        assertEquals(HarnessResult.Code.FULL, q.enqueue(s, "overflow").getCode());
    }

    @Test
    void builderRaisesAndLowersLimitsPerInstance() {
        QueueExtension big = QueueExtension.builder().maxSize(50).maxTextLength(16_384).build();
        AgentSession s = InMemoryAgentSession.of();
        assertTrue(big.enqueue(s, repeat(10_000)).isOk(), "超过缺省 4096 但在实例上限内");
        for (int i = 1; i < 50; i++) assertTrue(big.enqueue(s, "m" + i).isOk());
        assertEquals(HarnessResult.Code.FULL, big.enqueue(s, "overflow").getCode());
        assertEquals(50, big.pendingSize(s));

        QueueExtension small = QueueExtension.builder().maxSize(2).maxTextLength(5).build();
        AgentSession t = InMemoryAgentSession.of();
        assertEquals(HarnessResult.Code.INVALID, small.enqueue(t, "123456").getCode());
        assertEquals(1, small.enqueue(t, "a").getValue().intValue());
        assertEquals(2, small.enqueue(t, "b").getValue().intValue());
        assertEquals(HarnessResult.Code.FULL, small.enqueue(t, "c").getCode());
    }

    @Test
    void invalidLimitsRejected() {
        assertThrows(IllegalArgumentException.class, () -> QueueExtension.builder().maxSize(0));
        assertThrows(IllegalArgumentException.class, () -> QueueExtension.builder().maxTextLength(0));
        assertThrows(IllegalArgumentException.class, () -> QueueExtension.builder().maxSize(10_001));
    }
}
