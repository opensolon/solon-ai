package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

import java.time.Duration;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OpenRouterR1Test extends AbsThinkTest{
    private static final String apiUrl = "https://openrouter.ai/api/v1/chat/completions";
    private static final String apiKey = "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";
    private static final String model = "tngtech/deepseek-r1t2-chimera:free"; //deepseek/deepseek-r1-0528 //deepseek/deepseek-chat-v3-0324 //tngtech/deepseek-r1t2-chimera:free

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .timeout(Duration.ofSeconds(160))
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}