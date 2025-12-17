package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.annotation.Inject;
import org.noear.solon.test.SolonTest;


/**
 * @author noear 2025/4/8 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpChangeSyncTest {
    @Inject("McpServerTool2")
    private McpServerEndpointProvider serverEndpointProvider;

    @Test
    public void case1() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .apiUrl("http://localhost:8081/demo2/sse")
                .build();

        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 1;
        serverEndpointProvider.addTool(new FunctionToolDesc("hello")
                .description("打招呼")
                .doHandle((args) -> {
                    return "hello world";
                }));


        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 2;

        serverEndpointProvider.addTool(new FunctionToolDesc("hello2")
                .description("打招呼")
                .doHandle((args) -> {
                    return "hello world";
                }));


        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 3;

        serverEndpointProvider.removeTool("hello2");
        serverEndpointProvider.addTool(new FunctionToolDesc("hello2")
                .description("打招呼2222")
                .doHandle((args) -> {
                    return "hello world";
                }));

        Thread.sleep(10);
        mcpClient.clearCache();
        String rst2 = mcpClient.getTools().toString();
        System.out.println(rst2);
        assert rst2.contains("打招呼2222");


        serverEndpointProvider.removeTool("hello");
        serverEndpointProvider.removeTool("hello2");

        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 1;
        mcpClient.close();
    }

    @Test
    public void case2() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .apiUrl("http://localhost:8081/demo2/sse")
                .build();

        Thread.sleep(10);
        assert mcpClient.getTools().size() == 1;
        serverEndpointProvider.addTool(new FunctionToolDesc("hello")
                .description("打招呼")
                .doHandle((args) -> {
                    return "hello world";
                }));


        Thread.sleep(100);
        Assertions.assertEquals(2, mcpClient.getTools().size());

        serverEndpointProvider.addTool(new FunctionToolDesc("hello2")
                .description("打招呼")
                .doHandle((args) -> {
                    return "hello world";
                }));


        Thread.sleep(100);
        assert mcpClient.getTools().size() == 3;

        serverEndpointProvider.removeTool("hello2");
        serverEndpointProvider.addTool(new FunctionToolDesc("hello2")
                .description("打招呼2222")
                .doHandle((args) -> {
                    return "hello world";
                }));

        Thread.sleep(10);
        String rst2 = mcpClient.getTools().toString();
        System.out.println(rst2);
        assert rst2.contains("打招呼2222");


        serverEndpointProvider.removeTool("hello");
        serverEndpointProvider.removeTool("hello2");

        Thread.sleep(10);
        Assertions.assertEquals(1, mcpClient.getTools().size());
        mcpClient.close();
    }
}