package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekCh_v4Test extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
                .apiKey("sk-c380dfcdc1a64fbcb537a4cfdf83a0f2")
                .model("deepseek-v4-flash") //deepseek-v4-pro deepseek-v4-flash
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}