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
public class DashscopeDsTest {
    private static final Logger log = LoggerFactory.getLogger(DashscopeDsTest.class);
    private static final String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    private static final String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
    private static final String provider = "dashscope";
    private static final String model = "deepseek-v3";//"llama3.2"; //deepseek-r1:1.5b;

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .provider(provider)
                .model(model);
    }


    @Test
    public void case10() throws Exception {
        //没有参数的工具
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Case10Tools())
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