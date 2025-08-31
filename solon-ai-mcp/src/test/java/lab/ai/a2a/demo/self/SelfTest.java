package lab.ai.a2a.demo.self;

import lab.ai.a2a.A2AAgentAssistant;
import lab.ai.a2a.demo.Server1Tools;
import lab.ai.a2a.demo.Server2Tools;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.test.SolonTest;

import java.time.Duration;

/**
 *
 * @author noear 2025/8/31 created
 *
 */
@SolonTest
public class SelfTest {
    @Test
    public void hostAgent_call() throws Throwable {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .model("qwen2.5:latest")
                .provider("ollama")
                .timeout(Duration.ofMinutes(10))
                .defaultToolsAdd(new Server1Tools())
                .defaultToolsAdd(new Server2Tools())
                .build();

        InMemoryChatSession chatSession = InMemoryChatSession.builder()
                .maxMessages(10)
                .build();

        chatSession.addMessage(ChatMessage.ofUser("杭州今天的天气适合去哪里玩？"));

        ChatResponse chatResponse = chatModel
                .prompt(chatSession)
                .call();

        System.err.println(chatResponse.getContent());
    }
}
