package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列项值对象：构造归一化、状态机、持久化投影。
 *
 * <p>本测试必须与 {@link QueueItem} 同包：构造器与状态 setter 已收敛为包内可见
 * （宿主不构造队列项，入队一律走 {@code QueueExtension#enqueue}）。</p>
 */
class QueueItemTest {

    @Test
    void minimalConstructorFillsDefaults() {
        QueueItem item = new QueueItem("id1", "hello", InputOrigin.channel("cli"), 1000L, "PENDING");

        assertEquals("id1", item.getId());
        assertEquals("hello", item.getText());
        assertEquals("PENDING", item.getStatus());
        assertEquals(1000L, item.getCreatedAt());
        assertNotNull(item.getOrigin());
        assertNull(item.getModel());
        assertNull(item.getThinkingMode());
        assertNull(item.getSelectedAgent());
        assertNull(item.getClaimedAt());
        assertFalse(item.hasFiles());
    }

    @Test
    void blankIdIsGeneratedNotNull() {
        QueueItem a = new QueueItem(null, "t", InputOrigin.NONE, 0, null);
        assertNotNull(a.getId());
        assertTrue(a.getId().startsWith("q_"));

        QueueItem b = new QueueItem("   ", "t", InputOrigin.NONE, 0, null);
        assertNotNull(b.getId());
        assertTrue(b.getId().startsWith("q_"));

        // 非空 id 原样保留（含 trim 后判断）
        QueueItem c = new QueueItem(" keep ", "t", InputOrigin.NONE, 0, null);
        assertEquals(" keep ", c.getId());
    }

    @Test
    void zeroCreatedAtFallsBackToNow() {
        long before = System.currentTimeMillis();
        QueueItem item = new QueueItem("i", "t", InputOrigin.NONE, 0, null);
        assertTrue(item.getCreatedAt() >= before);

        QueueItem negative = new QueueItem("i", "t", InputOrigin.NONE, -5, null);
        assertTrue(negative.getCreatedAt() >= before);
    }

    @Test
    void statusIsNormalizedToOnlyTwoValues() {
        assertEquals("RUNNING", new QueueItem("i", "t", InputOrigin.NONE, 1, "running").getStatus());
        assertEquals("RUNNING", new QueueItem("i", "t", InputOrigin.NONE, 1, "RUNNING").getStatus());
        assertEquals("PENDING", new QueueItem("i", "t", InputOrigin.NONE, 1, "WHATEVER").getStatus());
        assertEquals("PENDING", new QueueItem("i", "t", InputOrigin.NONE, 1, null).getStatus());

        QueueItem item = new QueueItem("i", "t", InputOrigin.NONE, 1, "PENDING");
        item.setStatus("running");
        assertEquals("RUNNING", item.getStatus());
        item.setStatus("bogus");
        assertEquals("PENDING", item.getStatus());
    }

    @Test
    void nullOriginBecomesNoneInsteadOfNull() {
        QueueItem item = new QueueItem("i", "t", (InputOrigin) null, 1, null);
        assertNotNull(item.getOrigin());
        assertEquals(InputOrigin.NONE, item.getOrigin());
    }

    @Test
    void fullConstructorKeepsAllOptionalFields() {
        QueueItem item = new QueueItem("i", "t", InputOrigin.NONE, 1, "PENDING",
                "model-x", "high", "thinking", "sub-agent", true);

        assertEquals("model-x", item.getModel());
        assertEquals("high", item.getReasoningEffort());
        assertEquals("thinking", item.getThinkingMode());
        assertEquals("sub-agent", item.getSelectedAgent());
        assertTrue(item.hasFiles());
    }

    @Test
    void originCarriesChannelIdentity() {
        QueueItem item = new QueueItem("i", "t",
                InputOrigin.of("Feishu", "user-1", "chat-9", "msg-7"), 1, "PENDING");

        InputOrigin origin = item.getOrigin();
        assertEquals("Feishu", origin.getChannel());
        assertEquals("user-1", origin.getSenderId());
        assertEquals("chat-9", origin.getReplyTarget());
        assertEquals("msg-7", origin.getMessageId());
    }

    @Test
    void claimedAtIsSettable() {
        QueueItem item = new QueueItem("i", "t", InputOrigin.NONE, 1, "PENDING");
        assertNull(item.getClaimedAt());
        item.setClaimedAt(999L);
        assertEquals(999L, item.getClaimedAt());
        item.setClaimedAt(null);
        assertNull(item.getClaimedAt());
    }

    @Test
    void toMapExposesStableKeys() {
        QueueItem item = new QueueItem("i", "text", InputOrigin.NONE, 1234L, "PENDING");
        Map<String, Object> map = item.toMap();
        assertNotNull(map);
        assertEquals("i", map.get("id"));
        assertEquals("text", map.get("text"));
        assertEquals(1234L, map.get("createdAt"));
        assertEquals("PENDING", map.get("status"));
    }

    @Test
    void toViewIsTheDisplayContract() {
        QueueItem item = new QueueItem("i", "hello world", InputOrigin.channel("WEB"), 1, "PENDING");

        QueueItemView view = item.toView();

        assertEquals("i", view.getId());
        assertEquals("hello world", view.getText());
        assertEquals("PENDING", view.getStatus());
        assertFalse(view.isRunning());
        assertEquals("WEB", view.getSource());
        assertNotNull(view.toMap());
    }
}
