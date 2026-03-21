package demo.ai.mcp.server_ddd;

import io.modelcontextprotocol.server.transport.WebRxStatelessServerTransport;
import org.noear.solon.Solon;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.manager.McpServerHost;

/**
 *
 * @author noear 2026/3/21 created
 *
 */
public class AppTest {
    public static void main(String[] args) {
        McpServerEndpointProvider endpointProvider = McpServerEndpointProvider.builder()
                .name("McpServerTool2")
                .channel(McpChannel.STREAMABLE)
                .sseEndpoint("/mcp/demo2/sse")
                .build();

//        endpointProvider.addTool(new MethodToolProvider(new McpServerTool2()));
//        endpointProvider.addResource(new MethodResourceProvider(new McpServerTool2()));
//        endpointProvider.addPrompt(new MethodPromptProvider(new McpServerTool2()));

        McpServerHost serverHost = endpointProvider.getServer();
        WebRxStatelessServerTransport serverTransport1 = (WebRxStatelessServerTransport) serverHost.build();
        WebRxStatelessServerTransport serverTransport2 = null;
        WebRxStatelessServerTransport serverTransport3 = null;

        Solon.start(AppTest.class, args, app -> {
            app.router().get("/mcp", ctx -> {
                int role = ctx.paramAsInt("role");

                if (role == 1) {
                    serverTransport1.handleGet(ctx);
                } else if (role == 2) {
                    serverTransport2.handleGet(ctx);
                } else {
                    serverTransport3.handleGet(ctx);
                }
            });

            app.router().post("/mcp", ctx -> {
                int role = ctx.paramAsInt("role");

                if (role == 1) {
                    serverTransport1.handlePost(ctx);
                } else if (role == 2) {
                    serverTransport2.handlePost(ctx);
                } else {
                    serverTransport3.handlePost(ctx);
                }
            });
        });
    }
}
