package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekCh_v4Test extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api.deepseek.com/v1")
                .apiKey("sk-370a1c370c364e32a3ec6454945525fa")
                .standard(ChatDialects.OPENAI_RESPONSES)
                .model("deepseek-v4-flash") //deepseek-v4-pro deepseek-v4-flash
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}