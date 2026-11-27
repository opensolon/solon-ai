package features.ai.react.intercept;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 来源语义进入压缩输入的回归测试。 */
public class CompressionUtilFormattingTest {
    @Test
    public void sourcesAndCitationsShouldBeVisibleToCompressionStrategy() {
        AssistantMessage message = AssistantMessage.snapshot(
                "answer", "", null, null,
                Collections.singletonList(new SearchResult()
                        .title("Solon").url("https://solon.noear.org").snippet("Java framework")),
                Collections.singletonList(new Citation()
                        .title("Docs").url("https://solon.noear.org/docs").citedText("Solon AI")),
                null);

        String formatted = CompressionUtil.formatMessageForCompression(message);

        assertTrue(formatted.contains("[Source]"));
        assertTrue(formatted.contains("https://solon.noear.org"));
        assertTrue(formatted.contains("[Citation]"));
        assertTrue(formatted.contains("Solon AI"));
    }

    @Test
    public void mediaLeavesATraceInCompressionInput() {
        // 用户附件
        ChatMessage user = ChatMessage.ofUser("看这张", ImageBlock.ofBase64("iVBORw0KGgo=", "image/png").filePath("shot.png"));
        String u = CompressionUtil.formatMessageForCompression(user);
        assertTrue(u.contains("看这张"), u);
        assertTrue(u.contains("[Attached image/png: shot.png]"), u);
        assertFalse(u.contains("iVBORw0KGgo"), u);

        // 只有图片的工具结果（如 MCP 截图）不再只剩空的 Observation
        ToolResult result = new ToolResult().addBlock(ImageBlock.ofBase64("iVBORw0KGgo=", "image/jpeg"));
        String t = CompressionUtil.formatMessageForCompression(new ToolMessage(result, "screenshot", "c1", false));
        assertTrue(t.contains("[Attached image/jpeg]"), t);

        // 没有媒体时输出不变
        assertEquals("USER: hi", CompressionUtil.formatMessageForCompression(ChatMessage.ofUser("hi")));
    }
}
