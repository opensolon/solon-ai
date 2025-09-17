package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekTest extends AbsChatTest {
    private static final String apiUrl = "https://api.deepseek.com/v1/chat/completions";
    private static final String apiKey = "sk-cc0af00391474299b8642af78f601c88";
    private static final String model = "deepseek-chat"; //deepseek-reasoner//deepseek-chat

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}