package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/5/7 created
 */
@SolonTest
public class SiliconflowR1Test extends AbsThinkTest{
    private static final String apiUrl = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String apiKey = "sk-urlnakaoibyseycaocaaevuogdmxsiqvxmchurjhmhehdrhc";
    private static final String model = "deepseek-ai/DeepSeek-R1-Distill-Qwen-32B"; //"Qwen/Qwen2.5-72B-Instruct";// deepseek-ai/DeepSeek-R1-Distill-Qwen-32B

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }
}
