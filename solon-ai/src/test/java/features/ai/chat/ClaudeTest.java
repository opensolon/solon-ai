package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class ClaudeTest extends AbsChatTest{
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://api.vip.crond.dev/v1/messages")
                .apiKey("sk-***")
                .model("claude-opus-4-6");
    }
}