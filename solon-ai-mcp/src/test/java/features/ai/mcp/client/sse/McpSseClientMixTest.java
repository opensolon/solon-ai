package features.ai.mcp.client.sse;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.liquor.eval.Maps;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.test.SolonTest;

import java.util.List;

/**
 * @author noear 2025/5/1 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpSseClientMixTest {
    McpClientToolProvider mcpClient = McpClientToolProvider.builder()
            .apiUrl("http://localhost:8081/demo2/sse")
            .build();

    @Test
    public void tool() throws Exception {
        String response = mcpClient.callToolAsText("get_weather", Maps.of("location", "杭州"));

        assert Utils.isNotEmpty(response);
        log.warn("{}", response);
    }

    @Test
    public void resource() throws Exception {
        String resource = mcpClient.readResourceAsText("config://app-version");

        assert Utils.isNotEmpty(resource);
        log.warn("{}", resource);

        assert "v3.2.0".equals(resource);
    }

    @Test
    public void resource2() throws Exception {
        String resource = mcpClient.readResourceAsText("db://users/12/email");

        assert Utils.isNotEmpty(resource);
        log.warn("{}", resource);

        assert "12@example.com".equals(resource);
    }

    @Test
    public void prompt() throws Exception {
        List<ChatMessage> prompt = mcpClient.getPromptAsMessages("ask_question", Maps.of("topic", "教育"));

        assert Utils.isNotEmpty(prompt);
        log.warn("{}", prompt);
        assert prompt.size() == 1;
    }

    @Test
    public void prompt2() throws Exception {
        List<ChatMessage> prompt = mcpClient.getPromptAsMessages("debug_session", Maps.of("error", "太阳没出来"));

        assert Utils.isNotEmpty(prompt);
        log.warn("{}", prompt);
        assert prompt.size() == 2;
        assert prompt.get(0).getContent().contains("太阳");
    }
}
