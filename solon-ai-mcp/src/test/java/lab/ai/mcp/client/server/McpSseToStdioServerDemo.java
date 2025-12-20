package lab.ai.mcp.client.server;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

import java.util.Collection;

/**
 * 把 sse mcp-server 转为 stdio mcp-server
 */
@McpServerEndpoint(name = "sse-to-stdio-tool", channel = McpChannel.STDIO)
public class McpSseToStdioServerDemo implements ToolProvider {
    McpClientProvider sseToolProvider = McpClientProvider.builder()
            .url("http://localhost:8081/sse")
            .build();

    @Override
    public Collection<FunctionTool> getTools() {
        return sseToolProvider.getTools();
    }

    public static void main(String[] args) {
        Solon.start(McpSseToStdioServerDemo.class, args);
    }
}
