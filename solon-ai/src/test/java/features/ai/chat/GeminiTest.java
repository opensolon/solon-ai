package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GeminiTest extends AbsChatTest{
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://***.**/v1beta/models/gemini-3-flash-preview:generateContent")
                .apiKey("sk-****")
                .model("gemini-3-flash-preview");
    }
}