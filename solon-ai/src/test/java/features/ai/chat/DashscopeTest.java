package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DashscopeTest extends AbsChatTest{
    private static final String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    private static final String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
    private static final String provider = "dashscope";
    private static final String model = "qwen-turbo-latest";

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .provider(provider)
                .model(model);
    }
}