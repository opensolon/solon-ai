package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DashscopeOpenAiTest extends AbsChatTest{
    private static final String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
    private static final String model = "qwen-turbo-latest"; //"deepseek-r1-distill-qwen-1.5b";  //

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }
}