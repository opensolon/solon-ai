package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/5/7 created
 */
@SolonTest
public class BigmodelTest extends AbsChatTest{
    private static final String apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String apiKey = "52755d7995a8413783bb70ff6d44f42f.zCAKSzqlo9hmJS7s";
    private static final String model = "glm-4.5-flash";

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }
}
