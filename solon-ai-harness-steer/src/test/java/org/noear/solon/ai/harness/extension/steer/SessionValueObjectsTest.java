package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.HarnessResult;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插话结果：回执值对象与细分码契约。
 *
 * <p>细分码是线上协议（/web/chat/steer* 的应答码、前端 app-streaming.js 按字面值分支），
 * 取值必须与常量名去掉 {@code REASON_} 前缀后一致，集合也不得悄悄增减。</p>
 */
class SessionValueObjectsTest {

    @Test
    void reasonValuesEqualWireCodes() throws Exception {
        Set<String> values = new HashSet<>();
        for (Field f : SteerExtension.class.getFields()) {
            if (f.getName().startsWith("REASON_") && Modifier.isStatic(f.getModifiers())) {
                String value = (String) f.get(null);
                assertEquals(f.getName().substring("REASON_".length()), value, f.getName());
                values.add(value);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("NOT_RUNNING", "RUN_CHANGED", "DUPLICATE_STEER_ID", "NOT_PENDING",
                "BOX_FULL", "EMPTY_TEXT", "TEXT_TOO_LONG", "INVALID_STEER_ID")), values);
    }

    @Test
    void coarseCodesSplitParamsFromState() {
        assertEquals(HarnessResult.Code.INVALID, SteerMailbox.invalid(SteerExtension.REASON_EMPTY_TEXT).getCode());
        assertEquals(HarnessResult.Code.CONFLICT, SteerMailbox.conflict(SteerExtension.REASON_RUN_CHANGED).getCode());
        assertTrue(SteerMailbox.conflict(SteerExtension.REASON_RUN_CHANGED).hasReason(SteerExtension.REASON_RUN_CHANGED));
        assertEquals("RUN_CHANGED", SteerMailbox.conflict(SteerExtension.REASON_RUN_CHANGED).getReasonOrCode());
        assertEquals("DISABLED", HarnessResult.disabled("x").getReasonOrCode(), "无细分码时回落粗码名");
    }

    @Test
    void receiptGetters() {
        SteerReceipt steered = new SteerReceipt("s1", 0, 2);
        assertEquals("s1", steered.getSteerId());
        assertEquals(0, steered.getCanceled());
        assertEquals(2, steered.getPending());

        SteerReceipt batch = new SteerReceipt(null, 3, 1);
        assertNull(batch.getSteerId(), "批量撤销没有单条 steerId");
        assertEquals(3, batch.getCanceled());
        assertEquals(1, batch.getPending());
    }
}
