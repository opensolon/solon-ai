package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpTalentClient;
import org.noear.solon.test.SolonTest;

import java.util.Collection;

/**
 *
 * @author noear 2026/1/25 created
 *
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpTalentTest {
    @Test
    public void case1() {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://localhost:8081/talent/order")
                .build();

        Prompt prompt = Prompt.of("这个订单：A001，请查询订单详情。")
                .attrPut("tenant_id", "1")
                .attrPut("user_role", "user");

        McpTalentClient talentClient = new McpTalentClient(mcpClient);

        String description = talentClient.description();
        boolean isSupported = talentClient.isSupported(prompt);
        talentClient.onAttach(prompt);
        String instruction = talentClient.getInstruction(prompt);
        Collection<FunctionTool> tools = talentClient.getTools(prompt);

        log.info("description: {}", description);
        log.info("isSupported: {}", isSupported);
        log.info("instruction: {}", instruction);
        log.info("tools: {}", tools);

        assert isSupported;
        assert description.length() > 2;
        assert instruction.length() > 2;
        assert tools.size() == 1;

        prompt = Prompt.of("这个订单：A001，请查询订单详情。")
                .attrPut("tool", "all")
                .attrPut("tenant_id", "1")
                .attrPut("user_role", "user");

        tools = talentClient.getTools(prompt);
        assert tools.size() == 2;
    }
}
