package lab.ai.a2a.demo.a2a_remote;

import lab.ai.a2a.AgentTaskHandler;
import lab.ai.a2a.demo.a2a.Tools1;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

/**
 * @author noear 2025/8/31 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "mcp1")
public class Server1 implements AgentTaskHandler {
    ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
            .model("qwen2.5:1.5b")
            .provider("ollama")
            .defaultToolAdd(new Tools1())
            .build();

    @ToolMapping(name = "weather_agent", description = "专业的天气预报助手。主要任务是利用所提供的工具获取并传递天气信息")
    @Override
    public String handleTask(String message) throws Throwable {
        return chatModel.prompt(message).call().getMessage().getResultContent();
    }
}
