package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

import java.time.Duration;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OllamaR1Test extends AbsChatTest {
    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama") //需要指定供应商，用于识别接口风格（也称为方言）
                .model("deepseek-r1:1.5b")
                .timeout(Duration.ofSeconds(160));
    }
}