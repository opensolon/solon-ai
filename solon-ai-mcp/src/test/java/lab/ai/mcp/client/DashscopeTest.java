package lab.ai.mcp.client;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.Collection;

/**
 *
 * @author noear 2025/9/3 created
 *
 */
public class DashscopeTest {
    @Test
    public void case1() {
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.SSE)
                .apiUrl("https://dashscope.aliyuncs.com/api/v1/mcps/amap-maps/sse")
                .apiKey("xxx")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();
        for (FunctionTool tool : tools) {
            System.out.println(tool.inputSchema());
        }
    }
}
