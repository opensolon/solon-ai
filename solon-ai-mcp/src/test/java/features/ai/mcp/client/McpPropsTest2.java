package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/4/24 created
 */
@SolonTest(McpServerApp.class)
public class McpPropsTest2 {
    @Test
    public void case101() throws Exception {
        McpProviders tmp = McpProviders.fromMcpServers("classpath:mcpServers.json");

        assert tmp.size() == 2;
        tmp.close();
    }

    @Test
    public void case101b() throws Exception {
        McpProviders tmp = McpProviders.fromMcpServers("classpath:mcpServers.json");

        try {
            String str = tmp.getProvider("server1")
                    .callToolAsText("getWeather", Utils.asMap("location", "杭州"))
                    .getContent();

            System.out.println(str);
            assert str.contains("14度");
        } finally {
            tmp.close();
        }
    }

    @Test
    public void case102() throws Exception {
        McpProviders tmp = McpProviders.fromMcpServers("classpath:mcpServers2.json");

        assert tmp.size() == 2;
        tmp.close();
    }

    @Test
    public void case102b() throws Exception {
        McpProviders tmp = McpProviders.fromMcpServers("classpath:mcpServers2.json");

        try {
            String str = tmp.getProvider("server1")
                    .callToolAsText("getWeather", Utils.asMap("location", "杭州"))
                    .getContent();

            System.out.println(str);
            assert str.contains("14度");
        } finally {
            tmp.close();
        }
    }
}
