package features.ai.mcp.client;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.test.SolonTest;

@SolonTest(args = "-cfg=app-client.yml")
public class ConfigTest {
    @Test
    public void case1() {
        McpClientProperties config = Solon.cfg().toBean("solon.ai.mcp.client.proxy1", McpClientProperties.class);
        assert config != null;
        assert config.getHttpProxy() != null;
    }
}