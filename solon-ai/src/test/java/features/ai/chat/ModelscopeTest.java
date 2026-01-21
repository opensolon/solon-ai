package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class ModelscopeTest extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api-inference.modelscope.cn/v1/chat/completions")
                .apiKey("ms-ab936de2-2729-480c-8e36-aee72639e5bf")
                .model("deepseek-ai/DeepSeek-V3") //"Qwen/Qwen3-32B"; //"deepseek-ai/DeepSeek-V3"; //
                .modelOptions(m -> {
                    m.optionSet("enable_thinking", false);
                })
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}