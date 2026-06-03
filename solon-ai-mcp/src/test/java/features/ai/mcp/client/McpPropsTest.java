package features.ai.mcp.client;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.client.McpClientProviders;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/4/24 created
 */
@SolonTest(args = "-cfg=app-client-serverparams.yml")
public class McpPropsTest {
    @Test
    public void case1() {
        McpClientProperties clientProperties = Solon.cfg().toBean("solon.ai.mcp.client.demo1", McpClientProperties.class);

        assert McpChannel.STDIO.equalsIgnoreCase(clientProperties.getChannel());
        assert clientProperties.getArgs().size() == 2;
        assert "-jar".equalsIgnoreCase(clientProperties.getArgs().get(0));
        assert clientProperties.getEnv().size() == 1;
    }

    @Test
    public void case2() {
        McpClientProperties clientProperties = Solon.cfg().toBean("solon.ai.mcp.client.demo2", McpClientProperties.class);

        assert McpChannel.STDIO.equalsIgnoreCase(clientProperties.getChannel());
        assert clientProperties.getArgs().size() == 2;
        assert "-jar".equalsIgnoreCase(clientProperties.getArgs().get(0));
        assert clientProperties.getEnv().size() == 1;
    }

    @Test
    public void case101() throws Exception {
        McpClientProviders tmp = McpClientProviders.
                fromMcpServers("classpath:mcpServers.json");

        assert tmp.size() == 2;
        tmp.close();
    }

    @Test
    public void case102() throws Exception {
        McpClientProviders tmp = McpClientProviders.
                fromMcpServers("classpath:mcpServers2.json");

        assert tmp.size() == 2;
        tmp.close();
    }
}
