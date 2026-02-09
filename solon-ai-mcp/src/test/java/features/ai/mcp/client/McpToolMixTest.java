package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/5/11 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpToolMixTest {


    @Test
    public void case1() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://localhost:8081/mcp/WeatherTools/sse")
                .build();

        TextBlock mediaText = mcpClient.readResource("weather://cities");

        System.out.println(mediaText);

        assert "[Tokyo, Sydney, Tokyo]".equals(mediaText.getContent());
        mcpClient.close();
    }
}
