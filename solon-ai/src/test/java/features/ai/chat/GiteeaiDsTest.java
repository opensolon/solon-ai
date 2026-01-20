package features.ai.chat;

import features.ai.chat.tool.Case10Tools;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiDsTest {
    private static final Logger log = LoggerFactory.getLogger(GiteeaiDsTest.class);
    private static final String apiUrl = "https://ai.gitee.com/v1/chat/completions";
    private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    private static final String model = "DeepSeek-V3";//"QwQ-32B";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }

    @Test
    public void case10() throws Exception {
        //没有参数的工具
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Case10Tools())
                .build();

        String response = chatModel.prompt("杭州的假日景点介绍。要求用 tool 查")
                .call()
                .getMessage()
                .getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");
    }
}