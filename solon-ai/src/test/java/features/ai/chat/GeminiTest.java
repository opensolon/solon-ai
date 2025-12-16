package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GeminiTest extends AbsChatTest{
    private static final String apiUrl = "";
    private static final String apiKey = "";
    private static final String provider = "gemini";
    private static final String model = "xxx";

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .provider(provider)
                .model(model);
    }
}