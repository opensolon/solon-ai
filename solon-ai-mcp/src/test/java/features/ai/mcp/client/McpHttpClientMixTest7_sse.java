package features.ai.mcp.client;

import demo.ai.mcp.llm.LlmUtil;
import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author noear 2025/5/1 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpHttpClientMixTest7_sse {
    static McpClientProvider mcpClient = McpClientProvider.builder()
            .channel(McpChannel.SSE)
            .url("http://localhost:8081/demo7/sse?token=3")
            .headerSet("user", "2")
            .cacheSeconds(30)
            .build();


    @AfterAll
    public static void aft(){
        mcpClient.close();
    }

    @Test
    public void tool1_model() throws Exception {
        //没有参数的工具
        String response = mcpClient.callToolAsText("spotIntro", Collections.emptyMap()).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");


        ChatModel chatModel = LlmUtil.getChatModel()
                .defaultToolsAdd(mcpClient)
                .build();

        response = chatModel.prompt("杭州的假日景点介绍。要求用 tool 查")
                .call()
                .getMessage()
                .getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");
    }

    @Test
    public void tool2() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("city", "杭州");
        args.put("userName", "xxx");

        String response = mcpClient.callToolAsText("getCity", args).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("杭州:xxx");
    }

    @Test
    public void tool3() throws Exception {
        Map<String, Object> args = Utils.asMap("activityInfo",
                Utils.asMap("activityId", "12"));

        String response = mcpClient.callToolAsText("getDetails", args).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.equals("{\"activityId\":\"12\"}");
    }

    @Test
    public void tool3_result() throws Exception {
        Map<String, Object> args = Utils.asMap("activityInfo",
                Utils.asMap("activityId", "12"));

        String response = mcpClient.callToolAsText("getDetailsResult", args).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.equals("{\"code\":200,\"description\":\"\",\"data\":{\"activityId\":\"12\"}}")
                || response.equals("{\"code\":200,\"data\":{\"activityId\":\"12\"},\"description\":\"\"}");
    }

    @Test
    public void tool4_getHeader() throws Exception {
        String response = mcpClient.callToolAsText("getHeader", Utils.asMap()).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("2");
    }

    @Test
    public void tool5_getParam() throws Exception {
        String response = mcpClient.callToolAsText("getParam", Utils.asMap()).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("3");
    }

    @Test
    public void prompt1() throws Exception {
        List<ChatMessage> prompt = mcpClient.getPromptAsMessages("splitMessage", Collections.emptyMap());

        assert Utils.isNotEmpty(prompt);
        log.warn("{}", prompt);
        assert prompt.size() == 2;
        assert "[{role=user, content='', medias=[Image{url='https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png', b64_json='null', mimeType='image/jpeg'}]}, {role=user, content='这图里有方块吗？'}]"
                .equals(prompt.toString());
    }
}