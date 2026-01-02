package demo.ai.react;

import demo.ai.agent.LlmUtil;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.flow.FlowContext;

import java.time.LocalDateTime;

public class DemoApp {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent robot = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new TimeTool()))
                .build();

        FlowContext context = FlowContext.of("session_001");
        String answer = robot.call(context, "现在几点了？");

        System.out.println("Robot 答复: " + answer);
    }

    public static class TimeTool {
        @ToolMapping(description = "获取当前系统时间")
        public String getTime() {
            return LocalDateTime.now().toString();
        }
    }
}