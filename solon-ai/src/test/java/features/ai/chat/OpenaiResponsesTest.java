package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * OpenAI Responses API 方言测试
 * @author oisin lu
 * @date 2026年3月5日
 */
@SolonTest
public class OpenaiResponsesTest extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api.openai.com/v1/responses")
                .apiKey("sk-******")
                .provider("openai-responses")
                .model("gpt-5.3-codex");
    }
}
