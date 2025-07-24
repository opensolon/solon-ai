package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class ModelscopeTest extends AbsChatTest{
    private static final String apiUrl = "https://api-inference.modelscope.cn/v1/chat/completions";
    private static final String apiKey = "a90656bf-08b2-47c8-b791-f5be78fe15de";
    private static final String model = "deepseek-ai/DeepSeek-V3"; //"Qwen/Qwen3-32B";

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .defaultOptionAdd("enable_thinking", false)
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}