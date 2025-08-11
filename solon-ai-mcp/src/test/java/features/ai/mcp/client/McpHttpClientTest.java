package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.annotation.Inject;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.SolonTest;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;


/**
 * @author noear 2025/4/8 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class McpHttpClientTest {
    //简化客户端
    @Test
    public void case1() throws Exception {
        McpClientProvider mcpClient = Utils.loadProps("app-client.yml")
                .getProp("solon.ai.mcp.client.demo2")
                .toBean(McpClientProvider.class);

        String response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        assert Utils.isNotEmpty(response);
        log.warn("{}", response);

        mcpClient.close();
        mcpClient.reopen();

        response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        assert Utils.isNotEmpty(response);
        log.warn("{}", response);

        mcpClient.close();
    }

    @Test
    public void case1_2() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/demo2/sse")
                .build();

        String response = mcpClient.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();

        assert Utils.isNotEmpty(response);
        log.warn("{}", response);

        mcpClient.close();
    }

    @Inject("McpServerTool2")
    private McpServerEndpointProvider serverEndpointProvider;

    @Test
    public void case2() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/demo2/sse")
                .build();

        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 1;
        serverEndpointProvider.addTool(new FunctionToolDesc("hello")
                .description("打招呼")
                .doHandle((args) -> {
                    return "hello world";
                }));


        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 2;

        serverEndpointProvider.addTool(new FunctionToolDesc("hello2")
                .description("打招呼")
                .doHandle((args) -> {
                    return "hello world";
                }));


        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 3;

        serverEndpointProvider.removeTool("hello2");
        serverEndpointProvider.addTool(new FunctionToolDesc("hello2")
                .description("打招呼2222")
                .doHandle((args) -> {
                    return "hello world";
                }));

        mcpClient.clearCache();
        String rst2 = mcpClient.getTools().toString();
        System.out.println(rst2);
        assert rst2.contains("打招呼2222");


        serverEndpointProvider.removeTool("hello");
        serverEndpointProvider.removeTool("hello2");

        Thread.sleep(10);
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 1;
        mcpClient.close();
    }

    @Test
    public void case3() throws Exception {
        McpClientProvider mcpClient = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/demo2/sse")
                .build();

        assert mcpClient.getTools().size() == 1;
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 1;
        mcpClient.clearCache();
        assert mcpClient.getTools().size() == 1;
        mcpClient.close();
    }

    //与模型绑定
    @Test
    public void case4() throws Exception {
        McpClientProvider toolProvider = Utils.loadProps("app-client.yml")
                .getProp("solon.ai.mcp.client.demo2")
                .toBean(McpClientProvider.class);

        McpClientProvider toolProvider2 = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/demo4/sse")
                .build();

        ChatModel chatModel = ChatModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .defaultToolsAdd(toolProvider)
                .defaultToolsAdd(toolProvider2)
                .build();

        ChatResponse resp = chatModel
                .prompt("杭州天气和北京降雨量如何？借助 tool 回答")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());

        assert resp.getContent().contains("北京");
        toolProvider.close();
        toolProvider2.close();
    }

    @Test
    public void case4_stream() throws Exception {
        McpClientProvider toolProvider = Utils.loadProps("app-client.yml")
                .getProp("solon.ai.mcp.client.demo2")
                .toBean(McpClientProvider.class);

        McpClientProvider toolProvider2 = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/demo4/sse")
                .build();

        ChatModel chatModel = ChatModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .defaultToolsAdd(toolProvider)
                .defaultToolsAdd(toolProvider2)
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        chatModel.prompt("杭州天气和北京降雨量如何？")
                .stream()
                .subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnNext(resp -> {
                            respHolder.set(resp);
                        })
                        .doOnComplete(() -> {
                            latch.countDown();
                        }).doOnError(throwable -> {
                            latch.countDown();
                        }));

        latch.await();

        assert respHolder.get() != null;

        //打印消息
        log.info("{}", respHolder.get().getAggregationMessage());
        toolProvider.close();
        toolProvider2.close();
    }

    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;
}