package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class LlamaCppTest extends AbsChatTest{
    //http 用户代理
    private String userAgent = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/1.0 like claude-code; +https://solon.noear.org/)";

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("http://127.0.0.1:11434/")
                .apiKey(null)
                .standard(ChatDialects.OPENAI_COMPLETIONS)
                .model("ggml-org/Qwen3.8-27B-GGUF:Q4_K_M")
                .userAgent(userAgent);
    }
}