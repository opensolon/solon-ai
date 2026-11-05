package org.noear.solon.ai.harness.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.steer.SteerResult;
import org.noear.solon.ai.harness.extension.steer.SteerStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插话结果值对象：状态码与布尔判据。
 *
 * <p>队列项测试已移至 {@code session.queue.QueueItemTest}（与 QueueItem 同包），
 * 因为 QueueItem 的构造器与状态 setter 已收敛为包内可见。</p>
 */
class SessionValueObjectsTest {

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
