package demo.ai.harness;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;

public class LlmUtil {
    public static ChatModel getChatModel() {
        return ChatModel.of("https://apihub.agnes-ai.com/v1")
                .apiKey("sk-bu0RaoVbyiprgX3Pont70xyaoqZDsrvvEW36WmbTZev2wmBz")
                .standard(ChatDialects.OPENAI_COMPLETIONS)
                .model("agnes-3.0-flash")
                .build();
    }
}
