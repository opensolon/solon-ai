package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.input.InputOrigin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SteerMessage} 值对象语义（原 session.SessionQueueSteerTest 的插话载体部分）。
 */
class SteerMessageTest {

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

}
