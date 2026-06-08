package demo.ai.mcp.server;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/**
 *
 * @author noear 2025/9/23 created
 *
 */
@Configuration
public class ChatConfig {
    /**
     * 与大模型集成
     */
    @Bean
    public ChatModel chatModel() throws Exception {
        return ChatModel.of(_Constants.chat_apiUrl)
                .standard(_Constants.chat_standard)
                .model(_Constants.chat_model)
                .build();
    }
}
