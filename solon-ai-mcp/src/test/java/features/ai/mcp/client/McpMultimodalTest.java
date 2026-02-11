package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.resource.ResourceResult;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

/**
 * McpMultimodal 客户端测试类
 *
 * @author noear 2026/2/11 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpMultimodalTest {

    static McpClientProvider mcpClient = McpClientProvider.builder()
            .channel(McpChannel.STREAMABLE)
            .url("http://localhost:8081/multimodal/mc")
            .cacheSeconds(30)
            .build();

    @AfterAll
    public static void aft() {
        mcpClient.close();
    }

    // --- Tool 测试 (11-16) ---

    @Test
    public void tool_tests() throws Exception {
        // tool11: String
        Assertions.assertEquals("a", mcpClient.callTool("tool11", Utils.asMap("p1", "a")).getContent());

        // tool12: int
        Assertions.assertEquals("12", mcpClient.callTool("tool12", Utils.asMap("p1", 12)).getContent());

        // tool13: Bean (User)
        String res13 = mcpClient.callTool("tool13", Utils.asMap("p1", 1)).getContent();
        Assertions.assertTrue(res13.contains("\"userId\":1") && res13.contains("\"name\":\"a - 1\""));

        // tool14: CallToolResult
        ToolResult res14 = mcpClient.callTool("tool14", Utils.asMap("p1", 1));
        Assertions.assertEquals(2, res14.getBlocks().size());

        // tool15: TextContent
        Assertions.assertEquals("a", mcpClient.callTool("tool15", Utils.asMap("p1", 1)).getContent());

        // tool16: ToolResult
        ToolResult res16 = mcpClient.callTool("tool16", Utils.asMap("p1", 1));
        Assertions.assertTrue(res16.getBlocks().get(1) instanceof ImageBlock);
    }

    // --- Prompt 测试 (11-16) ---

    @Test
    public void prompt_tests() throws Exception {
        // prompt11: String
        Assertions.assertEquals("a", mcpClient.getPrompt("prompt11", Utils.asMap("p1", "a")).getFirstMessage().getContent());

        // prompt12: ChatMessage
        Assertions.assertEquals("a", mcpClient.getPrompt("prompt12", Utils.asMap("p1", "a")).getFirstMessage().getContent());

        // prompt13: List<ChatMessage>
        Assertions.assertEquals(1, mcpClient.getPrompt("prompt13", Utils.asMap("p1", "a")).size());

        // prompt14: Prompt
        Assertions.assertEquals(2, mcpClient.getPrompt("prompt14", Utils.asMap("p1", "a")).size());

        // prompt15: GetPromptResult
        Assertions.assertEquals(2, mcpClient.getPrompt("prompt15", Utils.asMap("p1", "a")).size());

        // prompt16: PromptMessage
        Assertions.assertEquals(1, mcpClient.getPrompt("prompt16", Utils.asMap("p1", "a")).size());
    }

    // --- Resource 测试 (11-16) ---

    @Test
    public void resource_tests() throws Exception {
        // resource11: String
        Assertions.assertEquals("p1", mcpClient.readResource("res://resource11").getContent());

        // resource12: byte[]
        Assertions.assertEquals("cDE=", mcpClient.readResource("res://resource12").getContent());

        // resource13: TextResourceContents
        Assertions.assertEquals("p1", mcpClient.readResource("res://resource13").getContent());

        // resource14: BlobResourceContents
        Assertions.assertEquals("cDE=", mcpClient.readResource("res://resource14").getContent());

        // resource15: ReadResourceResult
        Assertions.assertEquals("p1", mcpClient.readResource("res://resource15").getContent());

        // resource16: ResourceResult
        ResourceResult res16 = mcpClient.readResource("res://resource16");
        Assertions.assertEquals(2, res16.getResources().size());
        Assertions.assertTrue(res16.getContent().contains("p1"));
    }
}