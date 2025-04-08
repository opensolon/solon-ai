package features.ai.mcp.client;

import demo.ai.mcp.McpServerApp;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.mcp.client.McpClientWrapper;
import org.noear.solon.test.SolonTest;

import java.util.Map;


/**
 * @author noear 2025/4/8 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpClientTest {
    //原始客户端
    @Test
    public void case1() throws Exception {
        String baseUri = "http://localhost:" + Solon.cfg().serverPort();
        HttpClientSseClientTransport clientTransport = HttpClientSseClientTransport.builder(baseUri)
                .sseEndpoint("/mcp/sse")
                .build();

        McpSyncClient mcpClient = McpClient.sync(clientTransport).clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
                .build();

        mcpClient.initialize();

        McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest("sum", Map.of("a", 1, "b", 2));
        McpSchema.CallToolResult response = mcpClient.callTool(callToolRequest);

        assert response != null;
        log.warn("{}", response);

        mcpClient.close();
    }

    //简化客户端
    @Test
    public void case2() throws Exception {
        String baseUri = "http://localhost:" + Solon.cfg().serverPort();

        McpClientWrapper mcpClient = new McpClientWrapper(baseUri, "/mcp/sse");

        String response = mcpClient.callToolAsText("getWeather", Map.of("location", "杭州"));

        assert response != null;
        log.warn("{}", response);

        mcpClient.close();
    }

    //与模型绑定
    public void case3() throws Exception {
        ChatModel chatModel = null;
        McpClientWrapper mcpClient = null;

        chatModel.prompt("杭州今天的天气怎么样？")
                .options(options -> {
                    //转为函数集用于绑定
                    options.functionAdd(mcpClient.toFunctions());
                })
                .call();
    }
}