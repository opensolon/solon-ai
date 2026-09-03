package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/5/7 created
 */
@SolonTest
public class BigmodelTest extends AbsChatTest{

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                .apiKey("52755d7995a8413783bb70ff6d44f42f.zCAKSzqlo9hmJS7s")
                .model("glm-4.5-flash")
                .standard(ChatDialects.OPENAI_COMPLETIONS); //不支持其它
    }
}
