package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Collections;

/**
 * @author noear 2025/5/1 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpHttpClientTest8Body {
    static McpClientProvider mcpClient = McpClientProvider.builder()
            .channel(McpChannel.STREAMABLE)
            .apiUrl("http://localhost:8081/demo8/sse?user=1")
            .cacheSeconds(30)
            .build();


    @AfterAll
    public static void aft(){
        mcpClient.close();
    }

    @Test
    public void tool1() throws Exception {
        String response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
        assert "晴，15度".equals(response);
    }
}
