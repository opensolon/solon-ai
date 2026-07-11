package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatOptions;

/**
 * 统一 thinking 开关选项测试
 *
 * @since 4.0.4
 */
public class ThinkingOptionsTest {

    @Test
    @DisplayName("thinking(true/false) 写入 Boolean；null 移除")
    public void testThinkingSwitch() {
        ChatOptions o = ChatOptions.of();

        o.thinking(true);
        Assertions.assertEquals(Boolean.TRUE, o.option("thinking"));

        o.thinking(false);
        Assertions.assertEquals(Boolean.FALSE, o.option("thinking"));

        o.thinking(null);
        Assertions.assertNull(o.option("thinking"));
    }

    @Test
    @DisplayName("thinking 与 reasoning_effort 可并存于 options")
    public void testTogetherWithReasoningEffort() {
        ChatOptions o = ChatOptions.of()
                .thinking(true)
                .reasoning_effort("high");
        Assertions.assertEquals(Boolean.TRUE, o.option("thinking"));
        Assertions.assertEquals("high", o.option("reasoning_effort"));
    }
}
