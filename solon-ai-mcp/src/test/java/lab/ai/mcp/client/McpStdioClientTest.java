package lab.ai.mcp.client;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.liquor.eval.Maps;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/4/21 created
 */
@Slf4j
@SolonTest
public class McpStdioClientTest {
    @Test
    public void case1() throws Exception {
        //服务端不能开启控制台的日志，不然会污染协议流
        McpClientToolProvider mcpClient = McpClientToolProvider.builder()
                .channel(McpChannel.STDIO) //表示使用 stdio
                .serverParameters(McpServerParameters.builder("java")
                        .args("-jar", "/Users/noear/Downloads/demo-mcp-stdio/target/demo-mcp-stdio.jar")
                        .build())
                .build();

        //args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything", "dir")

        String response = mcpClient.callToolAsText("get_weather", Maps.of("location", "杭州"));

        assert response != null;
        log.warn(response);

        mcpClient.close();
    }
}
