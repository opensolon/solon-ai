package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatOptions;

/**
 * 统一 reasoning_effort 选项归一化测试
 *
 * @since 4.0.4
 */
public class ReasoningEffortOptionsTest {

    @Test
    @DisplayName("合法值归一化为小写并写入 options")
    public void testValidValues() {
        ChatOptions o = ChatOptions.of();

        o.reasoning_effort("LOW");
        Assertions.assertEquals("low", o.option("reasoning_effort"));

        o.reasoning_effort(" Medium ");
        Assertions.assertEquals("medium", o.option("reasoning_effort"));

        o.reasoning_effort("high");
        Assertions.assertEquals("high", o.option("reasoning_effort"));

        o.reasoning_effort("max");
        Assertions.assertEquals("max", o.option("reasoning_effort"));
    }

    @Test
    @DisplayName("null / auto / 空串移除选项")
    public void testRemoveValues() {
        ChatOptions o = ChatOptions.of().reasoning_effort("high");
        Assertions.assertEquals("high", o.option("reasoning_effort"));

        o.reasoning_effort(null);
        Assertions.assertNull(o.option("reasoning_effort"));

        o.reasoning_effort("medium");
        o.reasoning_effort("auto");
        Assertions.assertNull(o.option("reasoning_effort"));

        o.reasoning_effort("high");
        o.reasoning_effort("  ");
        Assertions.assertNull(o.option("reasoning_effort"));
    }

    @Test
    @DisplayName("非法值忽略，不污染 options")
    public void testInvalidIgnored() {
        ChatOptions o = ChatOptions.of().reasoning_effort("high");
        o.reasoning_effort("ultra");
        Assertions.assertEquals("high", o.option("reasoning_effort"));

        ChatOptions empty = ChatOptions.of().reasoning_effort("xhigh");
        Assertions.assertNull(empty.option("reasoning_effort"));
    }
}
