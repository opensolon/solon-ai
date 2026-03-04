package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Collections;

/**
 * @author noear 2025/5/16 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpRedirectTest5 {
    @Test
    public void tool1() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://localhost:8081/demo5/jump/sse")
                .build();

        String response = mcpClient.callTool("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        mcpClient.close();
    }
}
