package demo.ai.agent;

import org.noear.solon.ai.chat.ChatModel;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class LlmUtil {
    public static ChatModel getChatModel() {
        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
                .apiKey("sk-2095a01c43cd4964847cc04097f1c0d9")
                .model("deepseek-chat") //deepseek-reasoner//deepseek-chat
                .build();

//        return ChatModel.of("https://ai.gitee.com/v1/chat/completions")
//                .apiKey("THTNIFWBERJYNJJMLLAZ5B05FNAAXWBZALVAIA17")
//                .model("Qwen3-8B") //Qwen3-32B
//                .build();

//        return ChatModel.of("http://127.0.0.1:11434/api/chat")
//                .provider("ollama")
//                .model("qwen2.5:1.5b") //"llama3.2"; //"qwen2.5:1.5b"; //qwen3:4b;
//                .build();
    }
}
