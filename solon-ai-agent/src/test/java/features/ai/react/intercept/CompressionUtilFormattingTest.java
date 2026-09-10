package features.ai.react.intercept;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;

import java.util.Collections;

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
}
