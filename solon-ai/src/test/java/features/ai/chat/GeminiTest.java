package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GeminiTest extends AbsChatTest{
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("")
                .apiKey("")
                .provider("gemini")
                .model("");
    }
}