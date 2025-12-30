package org.noear.solon.ai.agent.multi;

import java.util.List;

/**
 *
 * @author noear 2025/12/31 created
 *
 */
public class AgentRouterPromptProviderEn implements AgentRouterPromptProvider {
    private static final AgentRouterPromptProvider instance = new AgentRouterPromptProviderEn();

    public static AgentRouterPromptProvider getInstance() {
        return instance;
    }

    @Override
    public String getSystemPrompt(String prompt, List<String> agentNames) {
        return "You are a team supervisor. \n" +
                "Global Task: " + prompt + "\n" +
                "Specialists: " + agentNames + ".\n" +
                "Instruction: Review the collaboration history and decide who should act next. \n" +
                "- If the task is finished, respond ONLY with 'FINISH'. \n" +
                "- Otherwise, respond ONLY with the specialist's name.";
    }
}