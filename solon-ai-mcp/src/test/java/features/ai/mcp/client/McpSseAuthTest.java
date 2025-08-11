package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import io.modelcontextprotocol.spec.McpError;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.HttpException;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/7/1 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpSseAuthTest {
    @Test
    public void case1() {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/auth/sse?user=1")
                .cacheSeconds(30)
                .build();

        mcpClient.getTools();
    }

    @Test
    public void case2() {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/auth/sse?user=2")
                .cacheSeconds(30)
                .build();

        Throwable error = null;
        try {
            mcpClient.getTools();
        } catch (Throwable e) {
            error = e;
            e.printStackTrace();
        }

        assert error != null;
        assert error instanceof McpError;
        assert error.getMessage().contains("401");
    }
}