package org.noear.solon.ai.harness.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.queue.SessionQueueItem;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.ai.harness.session.steer.SteerResult;
import org.noear.solon.ai.harness.session.steer.SteerStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话队列项与插话结果值对象：构造归一化、状态机、布尔判据。
 * 这些是宿主与外设（Web/IM/桌面）交互时的边界类型，字段语义必须稳定。
 */
class SessionValueObjectsTest {

    // ---------- SessionQueueItem ----------

    @Test
    void minimalConstructorFillsDefaults() {
        SessionQueueItem item = new SessionQueueItem("id1", "hello", "cli", 1000L, "PENDING");

        assertEquals("id1", item.getText().equals("hello") ? "id1" : "bad");
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
        SessionQueueItem a = new SessionQueueItem(null, "t", InputOrigin.NONE, 0, null);
        assertNotNull(a.getId());
        assertTrue(a.getId().startsWith("q_"));

        SessionQueueItem b = new SessionQueueItem("   ", "t", InputOrigin.NONE, 0, null);
        assertNotNull(b.getId());
        assertTrue(b.getId().startsWith("q_"));

        // 非空 id 原样保留（含 trim 后判断）
        SessionQueueItem c = new SessionQueueItem(" keep ", "t", InputOrigin.NONE, 0, null);
        assertEquals(" keep ", c.getId());
    }

    @Test
    void zeroCreatedAtFallsBackToNow() {
        long before = System.currentTimeMillis();
        SessionQueueItem item = new SessionQueueItem("i", "t", InputOrigin.NONE, 0, null);
        assertTrue(item.getCreatedAt() >= before);

        SessionQueueItem negative = new SessionQueueItem("i", "t", InputOrigin.NONE, -5, null);
        assertTrue(negative.getCreatedAt() >= before);
    }

    @Test
    void statusIsNormalizedToOnlyTwoValues() {
        assertEquals("RUNNING", new SessionQueueItem("i", "t", InputOrigin.NONE, 1, "running").getStatus());
        assertEquals("RUNNING", new SessionQueueItem("i", "t", InputOrigin.NONE, 1, "RUNNING").getStatus());
        assertEquals("PENDING", new SessionQueueItem("i", "t", InputOrigin.NONE, 1, "WHATEVER").getStatus());
        assertEquals("PENDING", new SessionQueueItem("i", "t", InputOrigin.NONE, 1, null).getStatus());

        SessionQueueItem item = new SessionQueueItem("i", "t", InputOrigin.NONE, 1, "PENDING");
        item.setStatus("running");
        assertEquals("RUNNING", item.getStatus());
        item.setStatus("bogus");
        assertEquals("PENDING", item.getStatus());
    }

    @Test
    void nullOriginBecomesNoneInsteadOfNull() {
        SessionQueueItem item = new SessionQueueItem("i", "t", (InputOrigin) null, 1, null);
        assertNotNull(item.getOrigin());
        assertEquals(InputOrigin.NONE, item.getOrigin());
    }

    @Test
    void fullConstructorKeepsAllOptionalFields() {
        SessionQueueItem item = new SessionQueueItem("i", "t", InputOrigin.NONE, 1, "PENDING",
                "model-x", "high", "thinking", "sub-agent", true);

        assertEquals("model-x", item.getModel());
        assertEquals("high", item.getReasoningEffort());
        assertEquals("thinking", item.getThinkingMode());
        assertEquals("sub-agent", item.getSelectedAgent());
        assertTrue(item.hasFiles());
    }

    @Test
    void sourceStyleConstructorBuildsOrigin() {
        SessionQueueItem item = new SessionQueueItem("i", "t", "Feishu", 1, "PENDING",
                "m", "e", "th", "ag", false, "user-1", "chat-9", "msg-7");

        InputOrigin origin = item.getOrigin();
        assertEquals("Feishu", origin.getChannel());
        assertEquals("user-1", origin.getSenderId());
        assertEquals("chat-9", origin.getReplyTarget());
        assertEquals("msg-7", origin.getMessageId());
    }

    @Test
    void claimedAtIsSettable() {
        SessionQueueItem item = new SessionQueueItem("i", "t", InputOrigin.NONE, 1, "PENDING");
        assertNull(item.getClaimedAt());
        item.setClaimedAt(999L);
        assertEquals(999L, item.getClaimedAt());
        item.setClaimedAt(null);
        assertNull(item.getClaimedAt());
    }

    @Test
    void toMapExposesStableKeys() {
        SessionQueueItem item = new SessionQueueItem("i", "text", InputOrigin.NONE, 1234L, "PENDING");
        Map<String, Object> map = item.toMap();
        assertNotNull(map);
        assertEquals("i", map.get("id"));
        assertEquals("text", map.get("text"));
        assertEquals(1234L, map.get("createdAt"));
        assertEquals("PENDING", map.get("status"));
    }

    // ---------- SteerResult ----------

    @Test
    void steerStatusCodesMatchNames() {
        for (SteerStatus s : SteerStatus.values()) {
            assertEquals(s.name(), s.code());
        }
    }

    @Test
    void steerResultPredicates() {
        SteerResult steered =
                new SteerResult(SteerStatus.STEERED, "s1", 2);
        assertTrue(steered.isSteered());
        assertFalse(steered.isBoxFull());
        assertFalse(steered.isNotRunning());
        assertEquals("s1", steered.getSteerId());
        assertEquals(2, steered.getQueued());
        assertEquals("STEERED", steered.getStatusCode());

        SteerResult full =
                new SteerResult(SteerStatus.BOX_FULL, "s2", 5);
        assertTrue(full.isBoxFull());
        assertFalse(full.isSteered());

        // NOT_RUNNING 与 TURN_CHANGED 都视为「未在运行」
        assertTrue(new SteerResult(
                SteerStatus.NOT_RUNNING, null, 0).isNotRunning());
        assertTrue(new SteerResult(
                SteerStatus.TURN_CHANGED, null, 0).isNotRunning());
        assertFalse(new SteerResult(
                SteerStatus.EMPTY_TEXT, null, 0).isNotRunning());
    }
}
