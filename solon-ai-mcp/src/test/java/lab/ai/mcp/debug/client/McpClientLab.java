package lab.ai.mcp.debug.client;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.Collections;

/**
 *
 * @author noear 2025/8/22 created
 *
 */
@Slf4j
public class McpClientLab {
    public static void main(String[] args) throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .apiUrl("http://localhost:8081/mcp/")
                .cacheSeconds(30)
                .build();

        String response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
    }
}
