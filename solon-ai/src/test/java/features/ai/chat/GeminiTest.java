package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GeminiTest extends AbsChatTest{
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://bearlab.ai")
                .standard(ChatDialects.GOOGLE_GENERATE)
                .apiKey("sk-3dVZMSV1Rt2oCuBocd5Rmwz3EqJljvTrs9gHObtI9t6mhspi")
                .model("gemini-3.5-flash-cli");

        // gemini-3-flash-cli
        // gemini-3.5-flash-cli
        // gemini-3.7-flash-high-cli
    }
}