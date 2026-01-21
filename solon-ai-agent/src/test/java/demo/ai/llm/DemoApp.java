package demo.ai.llm;

import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

import java.time.LocalDateTime;

public class DemoApp {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        SimpleAgent robot = SimpleAgent.of(chatModel)
                .defaultToolAdd(new TimeTool())
                .build();

        String answer = robot.prompt("现在几点了？").call().getContent();

        System.out.println("Robot 答复: " + answer);
    }

    public static class TimeTool {
        @ToolMapping(description = "获取当前系统时间")
        public String getTime() {
            return LocalDateTime.now().toString();
        }
    }
}