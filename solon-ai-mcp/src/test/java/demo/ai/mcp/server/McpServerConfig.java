package demo.ai.mcp.server;

import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

/**
 * @author noear 2025/4/17 created
 */
@Configuration
public class McpServerConfig {
    //用配置构建
    @Bean
    public McpServerEndpointProvider demo1(@Inject("${solon.ai.mcp.server.demo1}") McpServerEndpointProvider serverEndpoint,
                                           McpServerTool serverTool) {
        serverEndpoint.addTool(new MethodToolProvider(serverTool));

        return serverEndpoint;
    }

    //用构建器构建
    //@Bean
    public McpServerEndpointProvider demo2(McpServerTool2 serverTool) {
        McpServerEndpointProvider serverEndpoint = McpServerEndpointProvider.builder()
                .name("demo2")
                .mcpEndpoint("/demo2/sse")
                .build();

        serverEndpoint.addTool(new MethodToolProvider(serverTool));

        return serverEndpoint;
    }
}
