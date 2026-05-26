package lab.ai.a2a.demo.tool_only;

import demo.ai.mcp.llm.LlmUtil;
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
public class ToolOnlyTest {
    @Test
    public void hostAgent_call() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel()
                .timeout(Duration.ofMinutes(10))
                .defaultToolAdd(new Tools1())
                .defaultToolAdd(new Tools2())
                .build();


        ChatResponse chatResponse = chatModel
                .prompt("杭州今天的天气适合去哪里玩？")
                .call();

        System.err.println(chatResponse.getContent());
    }
}
