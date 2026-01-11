package demo.ai.react;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

import java.time.LocalDateTime;

public class DemoApp {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("***")
                .model("Qwen3-32B")
                .build();

        ReActAgent robot = ReActAgent.of(chatModel)
                .toolAdd(new MethodToolProvider(new TimeTool()))
                .build();

        String answer = robot.prompt("现在几点了？")
                .call()
                .getContent();

        System.out.println("Robot 答复: " + answer);

        //--------------------

        AgentSession session = InMemoryAgentSession.of("demo1");
        answer = robot.prompt("现在几点了？")
                .session(session) //会话记忆
                .call()
                .getContent();

        System.out.println("Robot 答复: " + answer);
    }

    public static class TimeTool {
        @ToolMapping(description = "获取当前系统时间")
        public String getTime() {
            return LocalDateTime.now().toString();
        }
    }
}