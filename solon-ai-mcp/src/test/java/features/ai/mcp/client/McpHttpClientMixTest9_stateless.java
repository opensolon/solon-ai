package features.ai.mcp.client;

import demo.ai.mcp.llm.LlmUtil;
import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.McpChannel;
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
public class McpHttpClientMixTest9_stateless {
    static McpClientProvider mcpClient = McpClientProvider.builder()
            .channel(McpChannel.STREAMABLE)
            .url("http://localhost:8081/demo9/mcp?token=3")
            .headerSet("user", "2")
            .cacheSeconds(30)
            .build();

    @AfterAll
    public static void aft(){
        mcpClient.close();
    }

    @Test
    public void tool1() throws Exception {
        String response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
    }

    @Test
    public void tool9() throws Exception {
        Collection<FunctionTool> tools = mcpClient.getTools();

        log.warn("{}", tools);

        assert tools.size() == 1;

        assert ("[FunctionToolDesc{name='getWeather', title='', description='查询天气预报', returnDirect=true, inputSchema={\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\",\"description\":\"城市位置\"}},\"required\":[\"location\"]}, outputSchema=null}]")
                .equals(tools.toString());
    }

    @Test
    public void tool9_model() throws Exception {
        ChatModel chatModel = LlmUtil.getChatModel()
                .defaultToolsAdd(mcpClient)
                .build();

        ChatResponse resp = chatModel
                .prompt("今天杭州的天气情况？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert resp.getMessage() != null;
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

        assert ("[FunctionResourceDesc{name='getAppVersion', uri='config://app-version', description='获取应用版本号', mimeType='text/config'}]").equals(resources.toString());
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
    public void prompt9() throws Exception {
        Collection<FunctionPrompt> prompts = mcpClient.getPrompts();
        System.out.println(prompts);
        assert prompts.size() == 2;
        assert prompts.stream().filter(p -> "askQuestion".equals(p.name())).count() == 1;
        assert prompts.stream().filter(p -> "debugSession".equals(p.name())).count() == 1;
    }
}
