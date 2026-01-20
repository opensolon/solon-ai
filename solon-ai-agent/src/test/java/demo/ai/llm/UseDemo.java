package demo.ai.llm;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.WebSearchRepository;
import org.noear.solon.annotation.Param;

import java.util.List;

/**
 *
 * @author noear 2026/1/13 created
 *
 */
public class UseDemo {
    @Test
    public void main() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建智能体：注入天气工具，设置推理温度
        ReActAgent agent = ReActAgent.of(chatModel)
                .chatOptions(o -> o.temperature(0.1F)) // 低温度保证推理逻辑的一致性
                .toolAdd(new MethodToolProvider(new SearchTools()))
                .build();

        String rst = agent.prompt("帮我查一下今年诺贝尔经济学奖得主的最新公开演讲，然后告诉我他演讲中提到的那个中国经济学家（关于债务问题）的主要观点是什么，最后用中文总结一下。")
                .call()
                .getContent();

        System.out.println(rst);
    }

    public static WebSearchRepository getWebSearchRepository() {
        String apiUrl = "https://api.bochaai.com/v1/web-search";
        String apiKey = "sk-043ee51e1b55487fa1a3ade35bd6682c";

        return WebSearchRepository.of(apiUrl).apiKey(apiKey).build();
    }

    public static class SearchTools {
        WebSearchRepository webSearchRepository = getWebSearchRepository();

        @ToolMapping(name = "search", description = "Search the web for the given query.")
        public String search(@Param(description = "The query to search for.") String query) throws Throwable {
            List<Document> documentList = webSearchRepository.search(query);

            return ONode.serialize(documentList);
        }
    }
}