package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiR1Test extends AbsThinkTest{
    private static final Logger log = LoggerFactory.getLogger(GiteeaiR1Test.class);
    private static final String apiUrl = "https://ai.gitee.com/v1/chat/completions";
    private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    private static final String model = "DeepSeek-R1";//"Qwen3-32B";//"QwQ-32B";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .reasoningFieldName("reasoning_content")
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}