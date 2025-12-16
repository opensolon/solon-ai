package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import io.modelcontextprotocol.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/7/1 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpHttpAuth2Test {
    @Test
    public void case1() {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .apiUrl("http://localhost:8081/auth2/sse")
                .header("role", "1")
                .cacheSeconds(30)
                .build();

        System.out.println(mcpClient.callToolAsText("getWeather", Utils.asMap("location", "杭州")));
        mcpClient.close();
    }

    @Test
    public void case2() {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .apiUrl("http://localhost:8081/auth2/sse")
                .header("role", "2")
                .cacheSeconds(30)
                .build();

        String rst = mcpClient.callToolAsText("getWeather", Utils.asMap("location", "杭州"))
                .getContent();

        assert rst.startsWith("Error:");
    }
}