package lab.ai.mcp.client;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.Collection;

/**
 * @author noear 2025/10/24 created
 */
public class Context7Test {
    @Test
    public void case1() {
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .apiUrl("https://mcp.context7.com/mcp")
                .header("CONTEXT7_API_KEY", "xxx")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();

        System.out.println("------------------------------------------------");
        System.out.println(tools);

        assert tools != null;
        assert tools.size() > 1;
    }
}
