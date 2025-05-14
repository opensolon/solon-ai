package features.ai.mcp.client.sse;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.test.SolonTest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author noear 2025/5/1 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpSseClientMixTest {
    McpClientProvider mcpClient = McpClientProvider.builder()
            .apiUrl("http://localhost:8081/demo2/sse")
            .build();

    @Test
    public void tool1() throws Exception {
        String response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
    }


    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;

    @Test
    public void tool2() throws Exception {
        //没有参数的工具
        String response = mcpClient.callToolAsText("spotIntro", Collections.emptyMap()).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");


        ChatModel chatModel = ChatModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .defaultToolsAdd(mcpClient)
                .build();

        response = chatModel.prompt("杭州有哪些景点适合周末玩的？")
                .call()
                .getMessage()
                .getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");
    }

    @Test
    public void tool9() throws Exception {
        Collection<FunctionTool> tools = mcpClient.getTools();

        log.warn("{}", tools);

        assert tools.size() == 1;

        assert ("[RefererFunctionTool{name='getWeather', " +
                "description='查询天气预报', " +
                "returnDirect=true, " +
                "inputSchema={\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\",\"description\":\"城市位置\"}},\"required\":[\"location\"]}}]")
                .equals(tools.toString());
    }

    @Test
    public void resource() throws Exception {
        String resource = mcpClient.readResourceAsText("config://app-version").getContent();

        assert Utils.isNotEmpty(resource);
        log.warn("{}", resource);

        assert "v3.2.0".equals(resource);

        Collection<FunctionResource> list = mcpClient.getResources();
        System.out.println(list);
        assert list.size() == 1;

        assert "config://app-version".equals(new ArrayList<>(list).get(0).uri());
    }

    @Test
    public void resource_tmpl() throws Exception {
        String resource = mcpClient.readResourceAsText("db://users/12/email").getContent();

        assert Utils.isNotEmpty(resource);
        log.warn("{}", resource);

        assert "12@example.com".equals(resource);

        Collection<FunctionResource> list = mcpClient.getResourceTemplates();
        System.out.println(list);
        assert list.size() == 1;

        assert "db://users/{user_id}/email".equals(new ArrayList<>(list).get(0).uri());
    }

    @Test
    public void resource9() throws Exception {
        Collection<FunctionResource> resources = mcpClient.getResources();
        System.out.println(resources);
        assert resources.size() == 1;

        assert ("[FunctionResourceDesc{name='getAppVersion', " +
                "uri='config://app-version', " +
                "description='获取应用版本号', " +
                "mimeType='text/config'}]").equals(resources.toString());
    }

    @Test
    public void prompt() throws Exception {
        List<ChatMessage> prompt = mcpClient.getPromptAsMessages("askQuestion", Collections.singletonMap("topic", "教育"));

        assert Utils.isNotEmpty(prompt);
        log.warn("{}", prompt);
        assert prompt.size() == 1;
    }

    @Test
    public void prompt2() throws Exception {
        List<ChatMessage> prompt = mcpClient.getPromptAsMessages("debugSession", Collections.singletonMap("error", "太阳没出来"));

        assert Utils.isNotEmpty(prompt);
        log.warn("{}", prompt);
        assert prompt.size() == 2;
        assert prompt.get(0).getContent().contains("太阳");
    }

    @Test
    public void prompt3() throws Exception {
        List<ChatMessage> prompt = mcpClient.getPromptAsMessages("splitMessage", Collections.emptyMap());

        assert Utils.isNotEmpty(prompt);
        log.warn("{}", prompt);
        assert prompt.size() == 2;
        assert "[{role=user, content='', medias=[Image{url='https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png', b64_json='null', mimeType='image/jpeg'}]}, {role=user, content='这图里有方块吗？'}]"
                .equals(prompt.toString());
    }

    @Test
    public void prompt9() throws Exception {
        Collection<FunctionPrompt> prompts = mcpClient.getPrompts();
        System.out.println(prompts);
        assert prompts.size() == 2;
        assert ("[FunctionPromptDesc{name='askQuestion', " +
                "description='生成关于某个主题的提问', " +
                "params=[ParamDesc{name='topic', description='主题', " +
                "required=true, " +
                "type=class java.lang.String}]}, " +
                "FunctionPromptDesc{name='debugSession', " +
                "description='初始化错误调试会话', " +
                "params=[ParamDesc{name='error', description='错误信息', " +
                "required=true, type=class java.lang.String}]}]").equals(prompts.toString());
    }
}
