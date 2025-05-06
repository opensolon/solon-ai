package features.ai.mcp.client.sse;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Map;

/**
 * @author noear 2025/4/24 created
 */
@SolonTest(args = "-cfg=app-client-serverparams.yml")
public class McpPropsTest {
    @Test
    public void case1() {
        McpClientProperties clientProperties = Solon.cfg().toBean("solon.ai.mcp.client.demo1", McpClientProperties.class);

        assert McpChannel.STDIO.equalsIgnoreCase(clientProperties.getChannel());
        assert clientProperties.getServerParameters().getArgs().size() == 2;
        assert "-jar".equalsIgnoreCase(clientProperties.getServerParameters().getArgs().get(0));
        assert clientProperties.getServerParameters().getEnv().size() == 1;
    }

    @Test
    public void case2() {
        McpClientProperties clientProperties = Solon.cfg().toBean("solon.ai.mcp.client.demo2", McpClientProperties.class);

        assert McpChannel.STDIO.equalsIgnoreCase(clientProperties.getChannel());
        assert clientProperties.getServerParameters().getArgs().size() == 2;
        assert "-jar".equalsIgnoreCase(clientProperties.getServerParameters().getArgs().get(0));
        assert clientProperties.getServerParameters().getEnv().size() == 1;
    }

    @Test
    public void case101() throws Exception {
        Map<String, McpClientProvider> tmp = McpClientProvider.
                fromMcpServers("classpath:mcpServers.json");

        assert tmp.size() == 2;
    }

    @Test
    public void case102() throws Exception {
        Map<String, McpClientProvider> tmp = McpClientProvider.
                fromMcpServers("classpath:mcpServers2.json");

        assert tmp.size() == 2;
    }
}
