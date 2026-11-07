package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.steer.SteerResult;
import org.noear.solon.ai.harness.extension.steer.SteerResult;

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
    void steerCodeNameIsEnumName() {
        for (SteerResult.Code s : SteerResult.Code.values()) {
            assertEquals(s.name(), new SteerResult(s, null, 0).getCode().name());
        }
    }

    @Test
    void steerResultPredicates() {
        SteerResult steered =
                new SteerResult(SteerResult.Code.STEERED, "s1", 2);
        assertTrue(steered.isSteered());
        assertFalse(steered.isBoxFull());
        assertFalse(steered.isNotRunning());
        assertEquals("s1", steered.getSteerId());
        assertEquals(2, steered.getPendingCount());
        assertEquals("STEERED", steered.getCode().name());

        SteerResult full =
                new SteerResult(SteerResult.Code.BOX_FULL, "s2", 5);
        assertTrue(full.isBoxFull());
        assertFalse(full.isSteered());

        // NOT_RUNNING 与 RUN_CHANGED 各有独立判定，不再混为一谈
        assertTrue(new SteerResult(
                SteerResult.Code.NOT_RUNNING, null, 0).isNotRunning());
        assertFalse(new SteerResult(
                SteerResult.Code.RUN_CHANGED, null, 0).isNotRunning());
        assertTrue(new SteerResult(
                SteerResult.Code.RUN_CHANGED, null, 0).isRunChanged());
        assertFalse(new SteerResult(
                SteerResult.Code.EMPTY_TEXT, null, 0).isNotRunning());
    }
}
