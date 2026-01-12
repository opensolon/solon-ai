package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekTest extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
                .apiKey("sk-011a4dfa2bbe4c7c8339bcaae41c949c")
                .model("deepseek-chat") //deepseek-reasoner//deepseek-chat
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}