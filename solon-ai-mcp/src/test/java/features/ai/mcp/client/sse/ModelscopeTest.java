package features.ai.mcp.client.sse;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.Collection;

/**
 * @author noear 2025/7/23 created
 */
public class ModelscopeTest {
    @Test
    public void case1() {
        //https://modelscope.cn/mcp/servers/@antvis/mcp-server-chart
        McpClientProvider clientProvider = McpClientProvider.builder()
                .apiUrl("https://mcp.api-inference.modelscope.net/381da551144140/sse")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();

        System.out.println("------------------------------------------------");
        System.out.println(tools);

        assert tools != null;
        assert tools.size() > 1;
    }
}
