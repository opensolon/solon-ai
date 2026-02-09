package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekR1ThinkTest extends AbsThinkTest{
    //JQC6M0GTNPGSCEXZOBUGUX0HVHCOLDIMN6XOSSSA
    private static final Logger log = LoggerFactory.getLogger(DeepSeekR1ThinkTest.class);
    private static final String apiUrl = "https://api.deepseek.com/v1/chat/completions";
    private static final String apiKey = "sk-19a568bbfc0248dfbac088a0a70fa74d";
    private static final String model = "deepseek-reasoner"; //deepseek-reasoner//deepseek-chat

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .timeout(Duration.ofSeconds(160));
    }
}