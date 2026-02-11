package demo.ai.mcp.server;

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.resource.ResourceResult;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;

import java.util.Base64;
import java.util.List;
import java.util.Arrays;

/**
 *
 * @author noear 2026/2/11 created
 *
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/multimodal/mc")
public class McpMultimodal {
    @ToolMapping
    public String tool11(@Param("p1") String p1) {
        return p1;
    }

    @ToolMapping
    public int tool12(@Param("p1") int p1) {
        return p1;
    }

    @ToolMapping
    public User tool13(@Param("p1") int p1) {
        return new User(p1);
    }

    @ToolMapping
    public McpSchema.CallToolResult tool14(@Param("p1") int p1) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("1")
                .addContent(new McpSchema.ImageContent(null, "xxxx", "image/png"))
                .build();
    }

    @ToolMapping
    public McpSchema.TextContent tool15(@Param("p1") int p1) {
        return new McpSchema.TextContent("a");
    }

    @ToolMapping
    public ToolResult tool16(@Param("p1") int p1) {
        return new ToolResult()
                .addText("1")
                .addBlock(ImageBlock.ofUrl("http://xxx.xxx.xxx/a.png"));
    }

    @PromptMapping
    public String prompt11(String p1) {
        return p1;
    }

    @PromptMapping
    public ChatMessage prompt12(String p1) {
        return ChatMessage.ofUser(p1);
    }

    @PromptMapping
    public List<ChatMessage> prompt13(String p1) {
        return Arrays.asList(ChatMessage.ofUser(p1));
    }

    @PromptMapping
    public Prompt prompt14(String p1) {
        return Prompt.of(ChatMessage.ofUser("p1"),
                ChatMessage.ofUser(p1));
    }

    @PromptMapping
    public McpSchema.GetPromptResult prompt15(String p1) {
        return new McpSchema.GetPromptResult("xxx",
                Arrays.asList(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(p1)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(p1))));
    }

    @PromptMapping
    public McpSchema.PromptMessage prompt16(String p1) {
        return new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(p1));
    }

    @ResourceMapping(uri = "res://resource11")
    public String resource11(String p1) {
        return p1;
    }

    @ResourceMapping(uri = "res://resource12")
    public byte[] resource12(String p1) {
        return p1.getBytes();
    }

    @ResourceMapping(uri = "res://resource13")
    public McpSchema.TextResourceContents resource13(String p1) {
        return new McpSchema.TextResourceContents("res://resource13", "text/plan", p1);
    }

    @ResourceMapping(uri = "res://resource14")
    public McpSchema.BlobResourceContents resource14(String p1) {
        return new McpSchema.BlobResourceContents("res://resource14", "text/plan", Base64.getEncoder().encodeToString(p1.getBytes()));
    }

    @ResourceMapping(uri = "res://resource15")
    public McpSchema.ReadResourceResult resource15(String p1) {
        return new McpSchema.ReadResourceResult(
                Arrays.asList(new McpSchema.TextResourceContents("res://resource13", "text/plan", p1))
        );
    }

    @ResourceMapping(uri = "res://resource16")
    public ResourceResult resource16(String p1) {
        return new ResourceResult()
                .addResource(TextBlock.of(p1))
                .addResource(BlobBlock.of(p1.getBytes(), null));
    }


    public static class User {
        int userId;
        String name;

        public User(int p1) {
            userId = p1;
            name = "a - " + p1;
        }
    }
}