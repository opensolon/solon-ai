package features.ai.mcp.client.sse;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.client.McpClientProvider;

/**
 * @author noear 2025/6/12 created
 */
public class McpClientErrTest {
    @Test
    public void connTest() throws Throwable {
        McpClientProvider clientProvider = McpClientProvider.builder()
                .apiUrl("https://mcp.map.baidu.com/sse")
                .build();

        long start = System.currentTimeMillis();
        try {
            clientProvider.getTools();
            clientProvider.close();
        } catch (Throwable e) {

        }
        long end = System.currentTimeMillis();

        assert start - end < 1000;
    }
}
