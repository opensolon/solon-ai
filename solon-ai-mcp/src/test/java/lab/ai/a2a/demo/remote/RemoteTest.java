package lab.ai.a2a.demo.remote;

import lab.ai.a2a.Agent;
import lab.ai.a2a.HostAgent;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.time.Duration;

/**
 * @author noear 2025/8/31 created
 */
@SolonTest(App.class) //用于启动 Server1 和 Server2
public class RemoteTest {
    @Test
    public void hostAgent_call() throws Throwable {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .model("qwen2.5:latest")
                .provider("ollama")
                .build();

        HostAgent hostAgent = new HostAgent(chatModel);

        hostAgent.register(agent1());
        hostAgent.register(agent2());

        ChatResponse chatResponse = hostAgent.chatCall("杭州今天的天气适合去哪里玩？");

        System.err.println(chatResponse.getContent());
    }

//    @Test
//    public void hostAgent_stream() throws Throwable {
//        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
//                .model("qwen2.5:latest")
//                .provider("ollama")
//                .build();
//
//        HostAgent hostAgent = new HostAgent(chatModel);
//
//        hostAgent.register(agent1());
//        hostAgent.register(agent2());
//
//        Flux.from(hostAgent.chatStream("杭州今天的天气适合去哪里玩？"))
//                .doOnNext(resp -> {
//                    System.err.println("------: " + resp.getContent());
//                })
//                .blockLast();
//    }

    public static ToolProvider agent1() {
        return McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://localhost:9001/mcp1")
                .timeout(Duration.ofSeconds(600))
                .build();
    }

    public static ToolProvider agent2() {
        return McpClientProvider.builder()
                .channel(McpChannel.STREAMABLE)
                .url("http://localhost:9001/mcp2")
                .timeout(Duration.ofSeconds(600))
                .build();
    }
}
