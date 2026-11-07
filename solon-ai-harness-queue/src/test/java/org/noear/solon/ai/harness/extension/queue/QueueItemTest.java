package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
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
        assertEquals(QueueItem.Status.PENDING, item.getStatus());
        assertEquals(1000L, item.getCreatedAt());
        assertNotNull(item.getOrigin());
        assertNull(item.getModel());
        assertTrue(item.getAttrs().isEmpty());
        assertNull(item.getSelectedAgent());
        assertNull(item.getClaimedAt());
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
        assertEquals(QueueItem.Status.RUNNING, new QueueItem("i", "t", InputOrigin.NONE, 1, "running").getStatus());
        assertEquals(QueueItem.Status.RUNNING, new QueueItem("i", "t", InputOrigin.NONE, 1, "RUNNING").getStatus());
        assertEquals(QueueItem.Status.PENDING, new QueueItem("i", "t", InputOrigin.NONE, 1, "WHATEVER").getStatus());
        assertEquals(QueueItem.Status.PENDING, new QueueItem("i", "t", InputOrigin.NONE, 1, null).getStatus());

        QueueItem item = new QueueItem("i", "t", InputOrigin.NONE, 1, "PENDING");
        item.setStatus(QueueItem.Status.RUNNING);
        assertEquals(QueueItem.Status.RUNNING, item.getStatus());
        item.setStatus(null);
        assertEquals(QueueItem.Status.PENDING, item.getStatus());
    }

    @Test
    void nullOriginBecomesNoneInsteadOfNull() {
        QueueItem item = new QueueItem("i", "t", (InputOrigin) null, 1, null);
        assertNotNull(item.getOrigin());
        assertEquals(InputOrigin.NONE, item.getOrigin());
    }

    @Test
    void fullConstructorKeepsAllOptionalFields() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("reasoningEffort", "high");
        QueueItem item = new QueueItem("i", "t", InputOrigin.NONE, 1, "PENDING",
                "model-x", "sub-agent", attrs);

        assertEquals("model-x", item.getModel());
        assertEquals("high", item.getAttrs().get("reasoningEffort"));
        assertEquals("sub-agent", item.getSelectedAgent());
    }

    @Test
    void legacyTopLevelEffortFieldsMergeIntoAttrs() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "i");
        row.put("text", "t");
        row.put("reasoningEffort", "high");
        row.put("thinkingMode", "deep");
        row.put("hasFiles", true);
        QueueItem item = QueueItem.fromMap(row);
        assertEquals("high", item.getAttrs().get("reasoningEffort"));
        assertEquals("deep", item.getAttrs().get("thinkingMode"));
        assertFalse(item.toPersistMap().containsKey("reasoningEffort"), "新格式只写 attrs");
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
    void persistMapExposesStableKeys() {
        QueueItem item = new QueueItem("i", "text", InputOrigin.NONE, 1234L, "PENDING");
        Map<String, Object> map = item.toPersistMap();
        assertNotNull(map);
        assertEquals("i", map.get("id"));
        assertEquals("text", map.get("text"));
        assertEquals(1234L, map.get("createdAt"));
        assertEquals("PENDING", map.get("status"));
    }

    @Test
    void toMapIsTheDisplayContract() {
        QueueItem item = new QueueItem("i", "hello world", InputOrigin.channel("WEB"), 1, "PENDING");

        QueueItem view = item;

        assertEquals("i", view.getId());
        assertEquals("hello world", view.getText());
        assertEquals(QueueItem.Status.PENDING, view.getStatus());
        assertFalse(view.isRunning());
        assertEquals("WEB", view.getSource());
        assertEquals("hello world", view.toMap().get("displayText"));
        assertEquals("WEB", view.toMap().get("source"));
        assertFalse(view.toMap().containsKey("sourceUserId"), "展示视图不得泄露持久化键");
    }
}
