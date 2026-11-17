package features.ai.mcp.client;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProviders;
import org.noear.solon.ai.mcp.client.McpServerParameters;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务描述：配置链路（参数 → Provider）与「未连接不建连」语义
 */
public class McpDescriptionTest {
    @Test
    public void builderDescription() {
        McpClientProvider provider = McpClientProvider.builder()
                .channel("sse")
                .url("http://127.0.0.1:1/mcp")
                .description("天气查询")
                .build();

        assertEquals("天气查询", provider.getDescription());
        assertNull(provider.getServerInfo(), "未连接时不为读取服务端信息而建连");
        assertNull(provider.getServerInstructions());
        provider.close();
    }

    @Test
    public void parametersPassDescription() throws Exception {
        McpServerParameters params = new McpServerParameters();
        params.setType("sse");
        params.setUrl("http://127.0.0.1:1/mcp");
        params.setDescription("地图服务");

        McpClientProvider provider = McpClientProviders.fromMcpServer(params);
        assertEquals("地图服务", provider.getDescription());
        provider.close();
    }

    @Test
    public void jsonConfigDescription() throws Exception {
        ONode config = ONode.ofJson("{\"mcpServers\":{\"maps\":{\"type\":\"sse\",\"url\":\"http://127.0.0.1:1/mcp\",\"description\":\"地图服务\"},"
                + "\"plain\":{\"type\":\"sse\",\"url\":\"http://127.0.0.1:1/mcp\"}}}");

        McpClientProviders providers = McpClientProviders.fromMcpServers(config);
        try {
            assertEquals("地图服务", providers.getProvider("maps").getDescription());
            assertNull(providers.getProvider("plain").getDescription(), "未配置时为 null");
        } finally {
            providers.close();
        }
    }
}
