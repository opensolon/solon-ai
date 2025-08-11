package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebRxStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.noear.solon.test.SolonTest;

/**
 *
 * @author noear 2025/8/11 created
 *
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpInitClientTest {
//    McpClientProvider mcpClient = McpClientProvider.builder()
//            .apiUrl("http://localhost:8081/demo2/sse?user=1")
//            .cacheSeconds(30)
//            .build();

    @Test
    public void sdkInit(){
        WebRxStreamableHttpTransport transport = WebRxStreamableHttpTransport
                .builder(new HttpUtilsBuilder().baseUri("http://localhost:8081/")).endpoint("/demo2/sse?user=1")
                .build();

        McpClient.SyncSpec builder = McpClient.sync(transport)
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build());

        McpSyncClient client = builder.build();

        client.initialize();

        client.listTools();
    }
}
