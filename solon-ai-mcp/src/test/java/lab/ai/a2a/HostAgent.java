package lab.ai.a2a;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.util.Map;

/**
 * 主智能体
 */
public class HostAgent {
    private final HostAgentAssistant agentAssistant = new HostAgentAssistant();
    private final ChatModel chatModel;

    public HostAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void register(FunctionTool agent) {
        agentAssistant.register(agent);
    }

    public void register(ToolProvider agentProvider) {
        for (FunctionTool agent : agentProvider.getTools()) {
            agentAssistant.register(agent);
        }
    }

    public ChatResponse chatCall(String prompt) throws IOException {
        return chatModel.prompt(buildSystemMessage(),
                        ChatMessage.ofUser(prompt))
                .options(o -> o.toolsAdd(agentAssistant))
                .call();
    }

    public ChatResponse chatCall(ChatSession session) throws IOException {
        session.addMessage(buildSystemMessage());

        return chatModel.prompt(session)
                .options(o -> o.toolsAdd(agentAssistant))
                .call();
    }

    public Publisher<ChatResponse> chatStream(String prompt) {
        return chatModel.prompt(buildSystemMessage(),
                        ChatMessage.ofUser(prompt))
                .options(o -> o.toolsAdd(agentAssistant))
                .stream();
    }

    public Publisher<ChatResponse> chatStream(ChatSession session) {
        session.addMessage(buildSystemMessage());

        return chatModel.prompt(session)
                .options(o -> o.toolsAdd(agentAssistant))
                .stream();
    }

    private SystemMessage buildSystemMessage() {
        StringBuilder systemPrompt = new StringBuilder(
                "您是一位擅长分配任务的专家，负责将用户请求分解为智能体可以执行的任务。能够将用户请求分配给合适的智能体。\n" +
                        "\n" +
                        "发现：\n" +
                        "- 你可以使用工具 `list_agents` 列出可用于分配任务的智能体。\n" +
                        "\n" +
                        "执行：\n" +
                        "- 对于可操作的请求，您可以使用工具 `send_message` 与智能体交互以获取结果。\n" +
                        " \n" +
                        "请在回复用户时包含智能体的名称。\n" +
                        "\n" +
                        "请依靠工具来处理请求，不要编造回复。如果您不确定，请向用户询问更多细节。\n" +
                        "主要关注对话的最新部分。将子任务的答案代入回答每个子任务的结果。\n" +
                        "\n" +
                        "智能体:\n");

        for (Map item : agentAssistant.list_agents()) {
            systemPrompt.append(item).append("\n");
        }

        return ChatMessage.ofSystem(systemPrompt.toString());
    }
}