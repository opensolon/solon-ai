package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OllamaTest extends AbsChatTest{
    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .provider(provider) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model);
    }
}