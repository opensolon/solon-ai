package lab.ai.mcp.client.server;

import io.modelcontextprotocol.client.transport.ServerParameters;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

import java.util.Collection;

/**
 * @author noear 2025/4/23 created
 */
@McpServerEndpoint(name = "stdio-tool")
public class McpStdioServerDemo implements ToolProvider {
    McpClientToolProvider stdioToolProvider = McpClientToolProvider.builder()
            .channel(McpChannel.STDIO) //表示使用 stdio
            .serverParameters(ServerParameters.builder("java")
                    .args("-jar", "/Users/noear/Downloads/demo-mcp-stdio/target/demo-mcp-stdio.jar")
                    .build())
            .build();

    @Override
    public Collection<FunctionTool> getTools() {
        return stdioToolProvider.getTools();
    }

    public static void main(String[] args) {
        Solon.start(McpStdioServerDemo.class, new String[]{"-server.port=8081"});
    }
}
