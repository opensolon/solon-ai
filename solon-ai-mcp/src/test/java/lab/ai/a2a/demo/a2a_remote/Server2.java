package lab.ai.a2a.demo.a2a_remote;

import lab.ai.a2a.AgentTaskHandler;
import lab.ai.a2a.demo.Server2Tools;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;

/**
 * @author noear 2025/8/31 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "mcp2")
public class Server2 implements AgentTaskHandler {
    ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
            .model("qwen2.5:latest")
            .provider("ollama")
            .defaultToolsAdd(new Server2Tools())
            .build();

    @ToolMapping(name = "spot_agent", description = "专业的景区推荐助手。主要任务是推荐景点信息")
    @Override
    public String handleTask(String message) throws Throwable {
        return chatModel.prompt(message).call().getMessage().getResultContent();
    }
}
