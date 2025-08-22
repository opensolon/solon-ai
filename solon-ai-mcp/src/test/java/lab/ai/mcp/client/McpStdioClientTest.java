package lab.ai.mcp.client;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.test.SolonTest;

import java.util.Collections;

/**
 * @author noear 2025/4/21 created
 */
@Slf4j
@SolonTest(scanning = false)
public class McpStdioClientTest {
    @Test
    public void case1() throws Exception {
        //服务端不能开启控制台的日志，不然会污染协议流
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STDIO) //表示使用 stdio
                .command("java")
                .args("-jar", "/Users/noear/Downloads/demo-mcp-stdio/target/demo-mcp-stdio.jar")
                .build();

        //args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything", "dir")

        String response = mcpClient.callToolAsText("get_weather", Collections.singletonMap("location", "杭州")).getContent();

        assert response != null;
        System.out.println(response);
        log.warn(response);

        mcpClient.close();
    }
}
