package lab.ai.mcp.case1;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/8/29 created
 */
@Slf4j
@SolonTest
public class BrowserMcpTest {
    //https://github.com/Euraxluo/browser-mcp
    @Test
    public void case1_sse() {
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.SSE)
                .url("http://0.0.0.0:8000/sse/")
                .build();

        log.warn("{}", clientProvider.getTools());

        ToolResult rst = clientProvider.callTool("get_browser_status", Utils.asMap());

        log.warn("{}", rst);
    }

    @Test
    public void case2_streamable() {
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://0.0.0.0:8000/mcp/")
                .build();

        log.warn("{}", clientProvider.getTools());

        ToolResult rst = clientProvider.callTool("get_browser_status", Utils.asMap());

        log.warn("{}", rst);
    }
}