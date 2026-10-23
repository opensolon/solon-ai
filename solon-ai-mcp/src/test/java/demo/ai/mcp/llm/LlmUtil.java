package demo.ai.mcp.llm;

import org.noear.solon.ai.chat.ChatModel;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class LlmUtil {

    public static ChatModel.Builder getChatModel() {
//        return ChatModel.of("https://api.deepseek.com/v1/chat/completions")
//                .apiKey("sk-8cc237b0657c4245a46832b0f6d9f930")
//                .model("deepseek-chat"); //deepseek-reasoner//deepseek-chat



        return ChatModel.of("https://apihub.agnes-ai.com/v1")
                .apiKey("sk-bu0RaoVbyiprgX3Pont70xyaoqZDsrvvEW36WmbTZev2wmBz")
                .model("agnes-3.0-flash"); //Qwen3-32B, GLM-4.6


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
