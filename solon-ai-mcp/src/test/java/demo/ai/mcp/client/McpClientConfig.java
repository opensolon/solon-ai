package demo.ai.mcp.client;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

@Configuration
public class McpClientConfig {
    @Bean
    public McpClientToolProvider clientWrapper(@Inject("${solon.ai.mcp.client.demo}") McpClientToolProvider client) {
        return client;
    }

    @Bean
    public ChatModel chatModel(@Inject("${solon.ai.chat.demo}") ChatConfig chatConfig, McpClientToolProvider toolProvider) {
        return ChatModel.of(chatConfig)
                .defaultToolsAdd(toolProvider)
                .build();
    }
}