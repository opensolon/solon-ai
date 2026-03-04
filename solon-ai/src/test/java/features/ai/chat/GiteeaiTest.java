package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiTest extends AbsChatTest{
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}