package lab.ai.mcp.client.server;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.ai.mcp.server.resource.ResourceProvider;

import java.util.Collection;

/**
 * 把 stdio mcp-server 转为 sse mcp-server
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, name = "stdio-to-sse-tool")
public class McpStdioToSseServerDemo implements ToolProvider, ResourceProvider {
    McpClientProvider stdioToolProvider = McpClientProvider.builder()
            .channel(McpChannel.STDIO) //表示使用 stdio
            .serverParameters(McpServerParameters.builder("java")
                    .args("-jar", "/Users/noear/Downloads/demo-mcp-stdio/target/demo-mcp-stdio.jar")
                    .build())
            .build();

    @Override
    public Collection<FunctionTool> getTools() {
        return stdioToolProvider.getTools();
    }

    public static void main(String[] args) {
        Solon.start(McpStdioToSseServerDemo.class, new String[]{"-server.port=8081"});
    }

    @Override
    public Collection<FunctionResource> getResources() {
        return stdioToolProvider.getResources();
    }
}
