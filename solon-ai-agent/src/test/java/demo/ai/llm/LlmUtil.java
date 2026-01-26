package demo.ai.llm;

import org.noear.solon.ai.chat.ChatModel;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class LlmUtil {
    public static ChatModel getChatModelReasoner() {
        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
                .apiKey("sk-e289372b9c5542dba143568e96c17b33")
                .model("deepseek-reasoner") //deepseek-reasoner//deepseek-chat
                .build();
    }
    public static ChatModel getChatModel() {
        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
                .apiKey("sk-19a568bbfc0248dfbac088a0a70fa74d")
                .model("deepseek-chat") //deepseek-reasoner//deepseek-chat
                .build();

//        return ChatModel.of("https://apis.iflow.cn/v1/chat/completions")
//                .apiKey("sk-663fb0149c999345efb114362ddba081")
//                .model("qwen3-32b")
//                .build();

//        return ChatModel.of("https://ai.gitee.com/v1/chat/completions")
//                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
//                .model("Qwen3-32B") //Qwen3-32B, GLM-4.6
//                .build();

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
