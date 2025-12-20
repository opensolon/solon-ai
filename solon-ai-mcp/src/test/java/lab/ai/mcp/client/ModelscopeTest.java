package lab.ai.mcp.client;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.impl.okhttp.OkHttpUtilsFactory;

import java.util.Collection;

/**
 * @author noear 2025/7/23 created
 */
public class ModelscopeTest {
    @Test
    public void case1() {
        //https://modelscope.cn/mcp/servers/@antvis/mcp-server-chart
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.SSE)
                .url("https://mcp.api-inference.modelscope.net/ec844bf2103647/sse")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();

        System.out.println("------------------------------------------------");
        System.out.println(tools);

        assert tools != null;
        assert tools.size() > 1;
    }

    @Test
    public void case1_okhttp() {
        //https://modelscope.cn/mcp/servers/@antvis/mcp-server-chart
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.SSE)
                .httpFactory(OkHttpUtilsFactory.getInstance())
                .url("https://mcp.api-inference.modelscope.net/ec844bf2103647/sse")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();

        System.out.println("------------------------------------------------");
        System.out.println(tools);

        assert tools != null;
        assert tools.size() > 1;
    }

    @Test
    public void case2() {
        //https://modelscope.cn/mcp/servers/chevalblanc/MCP-BING-CN
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("https://mcp.api-inference.modelscope.net/a6c83fbd332d4f/mcp")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();

        System.out.println("------------------------------------------------");
        System.out.println(tools);

        assert tools != null;
        assert tools.size() > 1;
    }

    @Test
    public void case2_okhttp() {
        //https://modelscope.cn/mcp/servers/chevalblanc/MCP-BING-CN
        McpClientProvider clientProvider = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .httpFactory(OkHttpUtilsFactory.getInstance())
                .url("https://mcp.api-inference.modelscope.net/a6c83fbd332d4f/mcp")
                .build();

        Collection<FunctionTool> tools = clientProvider.getTools();

        System.out.println("------------------------------------------------");
        System.out.println(tools);

        assert tools != null;
        assert tools.size() > 1;
    }
}