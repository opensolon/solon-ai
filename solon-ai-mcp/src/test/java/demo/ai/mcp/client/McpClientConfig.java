package demo.ai.mcp.client;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

@Configuration
public class McpClientConfig {
    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;

    @Bean
    public ChatModel chatModel(@Inject("${solon.ai.chat.demo}") ChatConfig config) {
        return ChatModel.of(config).build();
    }

    @Bean
    public McpClientToolProvider clientWrapper(@Inject("${solon.ai.mcp.client.demo}") McpClientToolProvider client) {
        return client;
    }
}