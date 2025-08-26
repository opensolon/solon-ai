package io.a2a_preview.client;

import io.a2a_preview.model.AgentCard;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
public class HostAgent {
    private ChatModel chatModel = null;
    private final ToolProvider agentTools;
    private final List<AgentCard> agentCards;

    // name - description
    private final List<Map<String, String>> agentInfo;

    public final Map<String, A2AClient> a2AClientMap;

    public HostAgent() {
        this.agentCards = new ArrayList<>();
        this.agentInfo = new ArrayList<>();
        this.a2AClientMap = new HashMap<>();
        this.agentTools = new MethodToolProvider(new HostAgentAssistantTools(this));
    }

    public List<Map<String, String>> getAgentInfo() {
        return agentInfo;
    }

    public List<AgentCard> getAgentCards() {
        return agentCards;
    }

    public Map<String, A2AClient> getA2AClientMap() {
        return a2AClientMap;
    }

    public void addChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void register(String remoteAddress) {

        if (remoteAddress.isEmpty()) {
            System.err.println("No remote address provided. Skipping agent registration.");
            return;
        }

        AgentCard agentCard = new A2AClient(remoteAddress).getAgentCard();
        agentCards.add(agentCard);

        a2AClientMap.put(agentCard.getName(), new A2AClient(remoteAddress));

        agentInfo.add(new HashMap<String, String>() {
            {
                put("agentName", agentCard.getName());
                put("description", agentCard.getDescription());
            }
        });
    }

    public ChatResponse chatCall(String prompt) throws IOException {
        return chatModel.prompt(buildSystemMessage(),
                        ChatMessage.ofUser(prompt))
                .options(o -> o.toolsAdd(agentTools))
                .call();
    }

    public ChatResponse chatCall(ChatSession session) throws IOException {
        session.addMessage(buildSystemMessage());

        return chatModel.prompt(session)
                .options(o -> o.toolsAdd(agentTools))
                .call();
    }

    public Publisher<ChatResponse> chatStream(String prompt) {
        return chatModel.prompt(buildSystemMessage(),
                        ChatMessage.ofUser(prompt))
                .options(o -> o.toolsAdd(agentTools))
                .stream();
    }

    public Publisher<ChatResponse> chatStream(ChatSession session) {
        session.addMessage(buildSystemMessage());

        return chatModel.prompt(session)
                .options(o -> o.toolsAdd(agentTools))
                .stream();
    }

    private SystemMessage buildSystemMessage() {
        StringBuilder systemPrompt = new StringBuilder(
                "您是一位擅长分配任务的专家，负责将用户请求分解为子代理可以执行的任务。能够将用户请求分配给合适的远程代理。\n" +
                        "\n" +
                        "发现：\n" +
                        "- 你可以使用工具 `list_remote_agents` 列出可用于分配任务的远程代理。\n" +
                        "\n" +
                        "执行：\n" +
                        "- 对于可操作的请求，您可以使用工具 `send_message` 与远程代理交互以获取结果。\n" +
                        " \n" +
                        "请在回复用户时包含远程代理的名称。\n" +
                        "\n" +
                        "请依靠工具来处理请求，不要编造回复。如果您不确定，请向用户询问更多细节。\n" +
                        "主要关注对话的最新部分。将子任务的答案代入回答每个子任务的结果。\n" +
                        "\n" +
                        "代理:\n");

        for (Map item : agentInfo) {
            systemPrompt.append(item).append("\n");
        }

        return ChatMessage.ofSystem(systemPrompt.toString());
    }
}
