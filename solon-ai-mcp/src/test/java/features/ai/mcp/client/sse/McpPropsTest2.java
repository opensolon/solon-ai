package features.ai.mcp.client.sse;

import demo.ai.mcp.server.McpServerApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Collections;
import java.util.Map;

/**
 * @author noear 2025/4/24 created
 */
@SolonTest(McpServerApp.class)
public class McpPropsTest2 {
    @Test
    public void case101() throws Exception {
        Map<String, McpClientProvider> tmp = McpClientProvider.
                fromMcpServers("classpath:mcpServers.json");

        assert tmp.size() == 2;
    }

    @Test
    public void case101b() throws Exception {
        Map<String, McpClientProvider> tmp = McpClientProvider.
                fromMcpServers("classpath:mcpServers.json");

        String str = tmp.get("server1").callToolAsText("getWeather", Collections.singletonMap("location", "杭州"))
                .getContent();

        System.out.println(str);
        assert str.contains("14度");
    }

    @Test
    public void case102() throws Exception {
        Map<String, McpClientProvider> tmp = McpClientProvider.
                fromMcpServers("classpath:mcpServers2.json");

        assert tmp.size() == 2;
    }

    @Test
    public void case102b() throws Exception {
        Map<String, McpClientProvider> tmp = McpClientProvider.
                fromMcpServers("classpath:mcpServers2.json");

        String str = tmp.get("server1").callToolAsText("getWeather", Collections.singletonMap("location", "杭州"))
                .getContent();

        System.out.println(str);
        assert str.contains("14度");
    }
}
