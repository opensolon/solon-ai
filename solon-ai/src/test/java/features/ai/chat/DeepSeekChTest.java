package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekChTest extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
                .apiKey("sk-4497bb4329fd478f84a1d6b7e44f3c11")
                .model("deepseek-chat") //deepseek-reasoner//deepseek-chat
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}