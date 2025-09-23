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
                .provider(_Constants.chat_provider)
                .model(_Constants.chat_model)
                .build();
    }
}
