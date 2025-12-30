package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import java.util.Arrays;
import java.util.List;

public class AgentRouterTask implements TaskComponent {
    private final ChatModel chatModel;
    private final List<String> agentNames;

    public AgentRouterTask(ChatModel chatModel, String... agentNames) {
        this.chatModel = chatModel;
        this.agentNames = Arrays.asList(agentNames);
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String prompt = context.getAs(Agent.KEY_PROMPT);
        String history = context.getOrDefault(Agent.KEY_HISTORY, "No progress yet.");

        String systemPrompt = "You are a team supervisor. Task: " + prompt + "\n" +
                "Available specialists: " + agentNames + ".\n" +
                "Based on the history, choose the NEXT specialist name or return 'FINISH' if the goal is met.\n" +
                "Respond ONLY with the name or 'FINISH'.";

        // 获取决策
        String decision = chatModel.prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Current Team Progress:\n" + history)
        )).call().getResultContent().toUpperCase();

        // 严格解析：防止模型返回 "The next agent is coder."
        String nextAgent = "end";
        if (!decision.contains("FINISH")) {
            for (String name : agentNames) {
                if (decision.contains(name.toUpperCase())) {
                    nextAgent = name;
                    break;
                }
            }
        }

        context.put(Agent.KEY_NEXT_AGENT, nextAgent);
    }
}