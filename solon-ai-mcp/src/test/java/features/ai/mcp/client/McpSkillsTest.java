package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import demo.ai.mcp.server.skills.OrderManagerSkillClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Collection;

/**
 *
 * @author noear 2026/1/25 created
 *
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpSkillsTest {
    @Test
    public void case1() {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://localhost:8081//skill/order")
                .build();

        Prompt prompt = Prompt.of("这个订单：A001，请查询订单详情。")
                .attrPut("tenant_id", "1")
                .attrPut("user_role", "user");

        OrderManagerSkillClient skillClient = new OrderManagerSkillClient(mcpClient);
        String description = skillClient.description();
        boolean isSupported = skillClient.isSupported(prompt);
        String instruction = skillClient.getInstruction(prompt);
        Collection<FunctionTool> tools = skillClient.getTools(prompt);

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

        tools = skillClient.getTools(prompt);
        assert tools.size() == 2;
    }
}
