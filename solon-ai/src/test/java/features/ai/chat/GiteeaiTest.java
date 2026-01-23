package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiTest extends AbsChatTest{
    //private static final String apiUrl = "https://ai.gitee.com/v1/chat/completions";
    //private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    //private static final String model = "gpt-oss-120b";//"GLM-4.6";//"Qwen3-32B";//"QwQ-32B";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}