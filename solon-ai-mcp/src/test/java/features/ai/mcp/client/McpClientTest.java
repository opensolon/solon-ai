package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.liquor.eval.Maps;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.test.SolonTest;

import java.util.Map;


/**
 * @author noear 2025/4/8 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpClientTest {
    //简化客户端
    @Test
    public void case2() throws Exception {
        McpClientToolProvider mcpClient = Utils.loadProps("app-client.yml")
                .getProp("solon.ai.mcp.client.demo")
                .toBean(McpClientToolProvider.class);

        String response = mcpClient.callToolAsText("getWeather", Maps.of("location", "杭州"));

        assert response != null;
        log.warn("{}", response);

        mcpClient.close();
    }

    //与模型绑定
    @Test
    public void case3() throws Exception {
        McpClientToolProvider toolProvider = Utils.loadProps("app-client.yml")
                .getProp("solon.ai.mcp.client.demo")
                .toBean(McpClientToolProvider.class);

        ChatModel chatModel = ChatModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();

        ChatResponse resp = chatModel
                .prompt("今天杭州的天气情况？")
                .options(options -> {
                    //转为工具集合用于绑定
                    options.toolsAdd(toolProvider);
                })
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;
}