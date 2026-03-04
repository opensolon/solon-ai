package demo.ai.mcp.client_ssl;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import reactor.core.publisher.Mono;

/**
 *
 * @author noear 2025/12/20 created
 *
 */
public class SamplingDemo {
    public void client1() {
        McpClientProvider clientProvider = McpClientProvider.builder()
                .url("http://localhost:8080/mcp")
                .customize(spec -> {
                    spec.capabilities(McpSchema.ClientCapabilities.builder().sampling().build());
                    spec.sampling(req -> Mono.just(McpSchema.CreateMessageResult.builder()
                            .content(new McpSchema.TextContent("test"))
                            .build()));
                })
                .build();


        clientProvider.callTool("demo", Utils.asMap("a", 1))
                .getContent();
    }

    @McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp")
    public static class Server1 {
        @ToolMapping(description = "demo")
        public Mono<McpSchema.CreateMessageResult> demo(McpAsyncServerExchange exchange) {
            return exchange.createMessage(McpSchema.CreateMessageRequest.builder().build());
        }
    }
}