package features.ai.mcp;

import demo.ai.agent.LlmUtil;
import demo.ai.mcp.McpServerTool2;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

/**
 *
 * @author noear 2026/1/13 created
 *
 */
@SolonTest(McpServerTool2.class)
public class McpTest {
    static McpClientProvider mcpClient = McpClientProvider.builder()
            .channel(McpChannel.STREAMABLE)
            .url("http://localhost:8080/mcp")
            .cacheSeconds(30)
            .build();

    @Test
    public void case1() throws Throwable {
        ReActAgent agent = ReActAgent.of(LlmUtil.getChatModel())
                .toolAdd(mcpClient)
                .build();

        String tmp = agent.prompt("杭州今天天气怎么样？")
                .call()
                .getContent();

        System.out.println(tmp);
    }
}
