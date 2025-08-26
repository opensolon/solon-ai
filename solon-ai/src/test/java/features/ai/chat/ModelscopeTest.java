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
    private static final String apiKey = "ms-ab936de2-2729-480c-8e36-aee72639e5bf";
    private static final String model = "deepseek-ai/DeepSeek-V3"; //"Qwen/Qwen3-32B"; //"deepseek-ai/DeepSeek-V3"; //

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .defaultOptionAdd("enable_thinking", false)
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}