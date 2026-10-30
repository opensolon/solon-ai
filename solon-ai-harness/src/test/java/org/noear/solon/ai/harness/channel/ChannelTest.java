package org.noear.solon.ai.harness.channel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel（IM 通道统一接口）契约测试：绑定关系与回复投递的接口语义，
 * 以最小实现桩验证三方法契约（接口本身无逻辑，此处锁定实现方应遵循的行为样板）。
 */
class ChannelTest {

    /** 最小通道实现：记录回复，模拟会话绑定表 */
    static class RecordingChannel implements Channel {
        final Map<String, Boolean> bindings = new HashMap<>();
        final List<String> replies = new ArrayList<>();

        RecordingChannel bind(String sessionId, boolean bound) {
            bindings.put(sessionId, bound);
            return this;
        }

        @Override
        public String getChannelName() {
            return "recording";
        }

        @Override
        public boolean isBound(String sessionId) {
            return bindings.getOrDefault(sessionId, false);
        }

        @Override
        public void sendReply(String sessionId, String text, boolean isFinal) {
            replies.add(sessionId + "|" + (isFinal ? "final" : "mid") + "|" + text);
        }
    }

    @Test
    void bindingContract() {
        RecordingChannel channel = new RecordingChannel().bind("s1", true);

        assertEquals("recording", channel.getChannelName());
        assertTrue(channel.isBound("s1"));
        assertFalse(channel.isBound("s2")); // 未绑定会话
    }

    @Test
    void replyDeliveryWithFinalityFlag() {
        RecordingChannel channel = new RecordingChannel();

        channel.sendReply("s1", "流式中间片段", false);
        channel.sendReply("s1", "最终答复", true);

        assertEquals(2, channel.replies.size());
        assertTrue(channel.replies.get(0).endsWith("|mid|流式中间片段"));
        assertTrue(channel.replies.get(1).endsWith("|final|最终答复"));
    }
}
