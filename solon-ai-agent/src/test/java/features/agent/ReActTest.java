package features.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;

/**
 *
 * @author noear 2025/11/30 created
 *
 */
public class ReActTest {
    @Test
    public void case1() throws Throwable {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .build();

        ToolProvider toolProvider = new MethodToolProvider(new Tools());

        ReActAgent reActAgent = new ReActAgent(chatModel, toolProvider.getTools(), 5);

        String question1 = "223 加上 777 的结果是多少？";
        System.out.println("\n❓ 问题 1: " + question1);

        // 运行 ReAct Agent
        String answer1 = reActAgent.run(question1);
        System.out.println("✅ 最终答案: " + answer1);

        System.out.println("\n--- 任务结束 ---");
    }
}
