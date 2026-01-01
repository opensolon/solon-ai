package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * English Prompt Provider (Supports all TeamStrategy protocols)
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamPromptProviderEn implements TeamPromptProvider {
    private static final TeamPromptProviderEn INSTANCE = new TeamPromptProviderEn();
    public static TeamPromptProviderEn getInstance() { return INSTANCE; }

    @Override
    public String getSystemPrompt(TeamConfig config, Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Role Setting\n");
        sb.append("You are the Team Mediator, responsible for coordinating the following agents to complete the task:\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- ").append(name).append(": ").append(agent.description()).append("\n");
        });

        sb.append("\n### Current Task\n").append(prompt.getUserContent()).append("\n");

        sb.append("\n### Collaboration Protocol: ").append(config.getStrategy()).append("\n");
        injectStrategyInstruction(sb, config.getStrategy(), config.getFinishMarker());

        sb.append("\n### Output Specification\n");
        sb.append("1. Analyze the current progress and decide the next action.\n");
        sb.append("2. If the task is fully completed, you MUST output: ").append(config.getFinishMarker()).append("\n");
        sb.append("3. Otherwise, output the name of the NEXT Agent to be executed.");

        return sb.toString();
    }

    private void injectStrategyInstruction(StringBuilder sb, TeamStrategy strategy, String finishMarker) {
        switch (strategy) {
            case HIERARCHICAL:
                sb.append("- You are the Lead Supervisor. Decompose the task into steps and assign the most suitable Agent.\n");
                sb.append("- You are responsible for reviewing each member's output to ensure it meets requirements.");
                break;
            case SWARM:
                sb.append("- You are a Dynamic Router. Agents operate in a peer-to-peer relay fashion.\n");
                sb.append("- Based on the previous Agent's result, determine who is best suited to handle the next 'baton'.");
                break;
            case CONTRACT_NET:
                sb.append("- Follow the 'Bidding-Awarding' protocol. If proposals haven't been gathered yet, you MUST output 'BIDDING'.\n");
                sb.append("- After receiving bids from Agents, compare their approaches and select one winner to execute.");
                break;
            case BLACKBOARD:
                sb.append("- The history is a public Blackboard. Check for missing information or logical gaps on the board.\n");
                sb.append("- Assign an Agent who can fill these gaps or correct errors.");
                break;
            case MARKET_BASED:
                sb.append("- Every agent is an independent service provider. Consider efficiency and expertise.\n");
                sb.append("- Select the Agent who can resolve the current issue with the fewest steps and highest quality.");
                break;
        }
    }
}