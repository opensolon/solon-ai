package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 来源身份值对象：合并四元组后的语义边界锁定。
 */
class InputOriginTest {

    @Test
    void noneIsEmptyAndReused() {
        assertTrue(InputOrigin.NONE.isEmpty());
        assertSame(InputOrigin.NONE, InputOrigin.of(null, null, null, null));
        assertSame(InputOrigin.NONE, InputOrigin.fromMap(null));
        assertTrue(InputOrigin.fromMap(new LinkedHashMap<>()).isEmpty());
    }

    @Test
    void toMapUsesLegacyKeysForBackwardCompatibility() {
        Map<String, String> map = InputOrigin.of("Feishu", "u1", "t1", "m1").toMap();

        // 键名必须沿用历史命名：队列快照与 session.replyRoute 的落盘数据依赖它
        assertEquals("Feishu", map.get("source"));
        assertEquals("u1", map.get("sourceUserId"));
        assertEquals("t1", map.get("replyTarget"));
        assertEquals("m1", map.get("messageId"));
        assertEquals(4, map.size());
    }

    @Test
    void toMapSkipsEmptyFields() {
        Map<String, String> map = InputOrigin.channel("WEB").toMap();

        assertEquals(1, map.size());
        assertEquals("WEB", map.get("source"));
    }

    @Test
    void fromMapRoundTrips() {
        InputOrigin origin = InputOrigin.of("WeChat", "u1", "ctx-token", null);

        assertEquals(origin, InputOrigin.fromMap(origin.toMap()));
    }

    @Test
    void fromMapAcceptsNonStringValues() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "DingTalk");
        raw.put("messageId", 12345L);

        InputOrigin origin = InputOrigin.fromMap(raw);

        assertEquals("DingTalk", origin.getChannel());
        assertEquals("12345", origin.getMessageId());
    }

    @Test
    void channelIsCaseInsensitiveBecauseChannelsUseInconsistentCase() {
        assertTrue(InputOrigin.channel("Feishu").channelIs("feishu"));
        assertTrue(InputOrigin.channel("CLI").channelIs("cli"));
        assertFalse(InputOrigin.NONE.channelIs("cli"));
        assertFalse(InputOrigin.channel("Feishu").channelIs(null));
    }

    @Test
    void equalsIgnoresChannelCase() {
        assertEquals(InputOrigin.channel("Feishu"), InputOrigin.channel("feishu"));
        assertEquals(InputOrigin.channel("Feishu").hashCode(), InputOrigin.channel("feishu").hashCode());
        // 其余字段仍精确匹配，避免不同来源被判为同一身份
        assertNotEquals(InputOrigin.of("Feishu", "u1", null, null), InputOrigin.of("Feishu", "u2", null, null));
    }

    @Test
    void channelOrFallsBackOnlyWhenEmpty() {
        assertEquals("CLI", InputOrigin.NONE.channelOr("CLI"));
        assertEquals("command", InputOrigin.channel("").channelOr("command"));
        assertEquals("Feishu", InputOrigin.channel("Feishu").channelOr("CLI"));
    }

    @Test
    void isImRecognizesOnlyImChannels() {
        assertTrue(InputOrigin.channel("wechat").isIm());
        assertTrue(InputOrigin.channel("Feishu").isIm());
        assertTrue(InputOrigin.channel("DingTalk").isIm());
        assertFalse(InputOrigin.channel("WEB").isIm());
        assertFalse(InputOrigin.channel("Loop").isIm());
        assertFalse(InputOrigin.channel("CLI").isIm());
        assertFalse(InputOrigin.NONE.isIm());
    }

    @Test
    void withReplyTargetKeepsIdentityAndRefreshesToken() {
        InputOrigin origin = InputOrigin.of("WeChat", "u1", "old-token", "m1");

        InputOrigin refreshed = origin.withReplyTarget("new-token");

        assertEquals("new-token", refreshed.getReplyTarget());
        assertEquals("WeChat", refreshed.getChannel());
        assertEquals("u1", refreshed.getSenderId());
        assertEquals("m1", refreshed.getMessageId());
        // 不可变：原实例不受影响
        assertEquals("old-token", origin.getReplyTarget());
    }

    @Test
    void withChannelKeepsOtherFields() {
        InputOrigin origin = InputOrigin.of(null, "u1", "t1", "m1");

        InputOrigin channelled = origin.withChannel("WEB");

        assertEquals("WEB", channelled.getChannel());
        assertEquals("u1", channelled.getSenderId());
        assertEquals("t1", channelled.getReplyTarget());
        assertEquals("m1", channelled.getMessageId());
    }
}
