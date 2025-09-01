package lab.ai.a2a.demo.a2a_local;

import lab.ai.a2a.AgentDispatcher;
import lab.ai.a2a.demo.Server1Tools;
import lab.ai.a2a.demo.Server2Tools;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.test.SolonTest;

import java.time.Duration;

/**
 * @author noear 2025/8/31 created
 */
@SolonTest
public class A2ALocalTest {
    @Test
    public void hostAgent_call() throws Throwable {
        AgentDispatcher agentAssistant = new AgentDispatcher();

        agentAssistant.register(agent1());
        agentAssistant.register(agent2());

        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .model("qwen2.5:latest")
                .provider("ollama")
                .timeout(Duration.ofMinutes(10))
                .defaultToolsAdd(agentAssistant)
                .build();

        InMemoryChatSession chatSession = InMemoryChatSession.builder()
                .systemMessages(agentAssistant.systemMessage())
                .maxMessages(10)
                .build();

        chatSession.addMessage(ChatMessage.ofUser("杭州今天的天气适合去哪里玩？"));

        ChatResponse chatResponse = chatModel
                .prompt(chatSession)
                .call();

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

    public static FunctionTool agent1() {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .model("qwen2.5:latest")
                .provider("ollama")
                .timeout(Duration.ofMinutes(10))
                .defaultToolsAdd(new Server1Tools())
                .build();

        return new FunctionToolDesc("weather_agent")
                .description("专业的天气预报助手。主要任务是利用所提供的工具获取并传递天气信息")
                .stringParamAdd("message", "任务消息")
                .doHandle(args -> {
                    String message = (String) args.get("message");
                    return chatModel.prompt(message).call().getMessage().getResultContent();
                });
    }

    public static FunctionTool agent2() {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .model("qwen2.5:latest")
                .provider("ollama")
                .timeout(Duration.ofMinutes(10))
                .defaultToolsAdd(new Server2Tools())
                .build();

        return new FunctionToolDesc("spot_agent")
                .description("专业的景区推荐助手。主要任务是推荐景点信息")
                .stringParamAdd("message", "任务消息")
                .doHandle(args -> {
                    String message = (String) args.get("message");
                    return chatModel.prompt(message).call().getMessage().getResultContent();
                });
    }
}