package lab.ai.mcp.debug.client;

import lab.ai.mcp.debug.server.McpApp;
import lab.ai.mcp.debug.server.McpServerEndpointDemo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.prompt.PromptResult;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.test.SolonTest;

import java.util.*;

/**
 * @author noear 2025/5/1 created
 */
@Slf4j
@SolonTest(McpApp.class)
public class McpClientTest {
    McpClientProvider mcpClient = McpClientProvider.builder()
            .channel(McpChannel.STREAMABLE)
            .url("http://localhost:8081/mcp/")
            .cacheSeconds(30)
            .build();

    @Test
    public void tool1() throws Exception {
        Collection<FunctionTool> tools = mcpClient.getTools();

        log.warn("{}", tools);

        Assertions.assertEquals(3, tools.size());
    }

    @Test
    public void tool2() throws Exception {
        String response = mcpClient.callTool("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
    }

    @Test
    public void tool3_async() throws Exception {
        String response = mcpClient.callTool("getWeatherAsync", Collections.singletonMap("location", "杭州")).getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
    }

    @Test
    public void tool4_jsonschame() throws Exception {
        MethodToolProvider provider = new MethodToolProvider(new McpServerEndpointDemo());
        Map<String, FunctionTool> toolMap = new HashMap<>();
        for (FunctionTool t1 : provider.getTools()) {
            toolMap.put(t1.name(), t1);
        }

        String getWeatherAsync = toolMap.get("getWeatherAsync").outputSchema();
        String getOrderInfo = toolMap.get("getOrderInfo").outputSchema();

        System.out.println(getWeatherAsync);
        System.out.println(getOrderInfo);

        assert "".equals(getWeatherAsync);
        assert "{\"type\":\"object\",\"properties\":{\"created\":{\"type\":\"string\",\"format\":\"date-time\"},\"id\":{\"type\":\"integer\"},\"title\":{\"type\":\"string\"}},\"required\":[]}"
                .equals(getOrderInfo);
    }

    @Test
    public void resource() throws Exception {
        String resource = mcpClient.readResource("config://app-version").getContent();

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
        String resource = mcpClient.readResource("db://users/12/email").getContent();

        assert Utils.isNotEmpty(resource);
        log.warn("resource-get: {}", resource);

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

        assert ("[FunctionResourceDesc{name='getAppVersion', uri='config://app-version', description='获取应用版本号', mimeType='text/config'}]")
                .equals(resources.toString());
    }

    @Test
    public void prompt() throws Exception {
        PromptResult prompt = mcpClient.getPrompt("askQuestion", Collections.singletonMap("topic", "教育"));

        assert Utils.isNotEmpty(prompt.getMessages());
        log.warn("{}", prompt);
        assert prompt.size() == 1;
    }

    @Test
    public void prompt2() throws Exception {
        PromptResult prompt = mcpClient.getPrompt("debugSession", Collections.singletonMap("error", "太阳没出来"));

        assert Utils.isNotEmpty(prompt.getMessages());
        log.warn("{}", prompt);
        assert prompt.size() == 2;
        assert prompt.getMessages().get(0).getContent().contains("太阳");
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