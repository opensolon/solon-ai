package org.noear.solon.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.input.HarnessInput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandOutput 接口默认方法与缓冲实现单元测试：color 的 ANSI 剥离与 supportsAnsi 透传、
 * 默认无意图/无缓冲回执；BufferedCommandOutput 收集回执与意图。
 */
class CommandOutputDefaultsTest {

    static class Base implements CommandOutput {
        @Override
        public void println(String text) {
        }

        @Override
        public void requestAgentTask(String prompt, String model) {
        }
    }

    @Test
    void defaultsAreSafe() {
        Base out = new Base();

        assertFalse(out.supportsAnsi());        // 默认不支持 ANSI
        assertNull(out.getAgentTaskIntent());   // 未声明意图
        assertNull(out.getText());              // 默认无缓冲回执
    }

    @Test
    void colorStripsAnsiWhenUnsupported() {
        Base out = new Base();
        // 不支持 ANSI：转义序列被剥离（/model、/rewind 等命令的回执在 Web 端的形态）
        assertEquals("已回退 1 条记录", out.color("\033[2m已回退 1 条记录\033[0m"));
        assertEquals("纯文本", out.color("纯文本"));
    }

    @Test
    void colorKeepsAnsiWhenSupported() {
        CommandOutput out = new Base() {
            @Override
            public boolean supportsAnsi() {
                return true;
            }
        };
        assertEquals("\033[1m加粗\033[0m", out.color("\033[1m加粗\033[0m"));
    }

    @Test
    void bufferedOutputCollectsLines() {
        BufferedCommandOutput out = new BufferedCommandOutput();
        assertNull(out.getText(), "无输出时返回 null，由调用方决定默认文案");

        out.println("第一行");
        out.println("第二行");

        assertEquals("第一行\n第二行\n", out.getText());
        assertFalse(out.supportsAnsi());
        assertNull(out.getAgentTaskIntent(), "未声明意图时为空");
    }

    @Test
    void bufferedOutputRecordsIntent() {
        BufferedCommandOutput out = new BufferedCommandOutput();
        out.requestAgentTask("go on", "m1");

        assertEquals("go on", out.getAgentTaskIntent().getPrompt());
        assertEquals("m1", out.getAgentTaskIntent().getModel());

        out.requestAgentTask(null, null);
        // 续流意图用空正文表达：分发器据此走续流装配，而不是被空输入判空拦成 IGNORED
        assertTrue(out.getAgentTaskIntent().isResume());
    }

    @Test
    void bufferedFactoryIsStateless() {
        CommandInput in = CommandInput.of(HarnessInput.of(null, "/x"),
                ParsedCommand.parse("/x"), null);
        CommandOutput a = BufferedCommandOutputFactory.INSTANCE.create(in);
        CommandOutput b = new BufferedCommandOutputFactory().create(in);

        assertTrue(a instanceof BufferedCommandOutput);
        assertFalse(a == b, "每次创建新的输出，互不串扰");
    }
}
