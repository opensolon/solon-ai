


package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * English Prompt Provider (Supports all TeamStrategy protocols) - Optimized
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

        sb.append("\n### Historical Context Analysis Rules\n");
        sb.append("You will receive complete collaboration history containing outputs from each member.\n");
        sb.append("Carefully analyze if the information in history is sufficient to answer the user's question.\n");
        sb.append("If history already contains complete information, you can provide the final answer directly.\n");
        sb.append("If specific expert input is still needed, assign the appropriate member.");

        sb.append("\n### Termination Condition Assessment\n");
        sb.append("The task should be considered complete when:\n");
        sb.append("1. The user's question has been adequately answered\n");
        sb.append("2. All necessary information is presented in the history\n");
        sb.append("3. Continuing execution won't produce new valuable information\n");
        sb.append("4. Maximum iteration limit has been reached\n");
        sb.append("5. Circular execution pattern is detected");

        sb.append("\n### Output Specification\n");
        sb.append("1. Thoroughly analyze collaboration history and assess current progress\n");
        sb.append("2. If the task is fully completed, you MUST output: ").append(config.getFinishMarker()).append(" Your final answer\n");
        sb.append("3. Otherwise, output ONLY the name of the NEXT Agent to execute (no additional content)\n");
        sb.append("4. Ensure your decision is based on historical records and task requirements");

        sb.append("\n### Special Notes\n");
        sb.append("- Ensure each step has a clear purpose\n");
        sb.append("- Avoid asking for the same information repeatedly\n");
        sb.append("- Prioritize members who can provide maximum value\n");
        sb.append("- Maintain coherence and completeness in responses");

        return sb.toString();
    }

    private void injectStrategyInstruction(StringBuilder sb, TeamStrategy strategy, String finishMarker) {
        switch (strategy) {
            case HIERARCHICAL:
                sb.append("- You are the Lead Supervisor. Decompose the task into steps and assign the most suitable Agent.\n");
                sb.append("- You are responsible for reviewing each member's output to ensure it meets requirements.\n");
                sb.append("- Example decision flow: Analyze task → Decompose steps → Assign members → Check results → Continue or finish");
                break;
            case SWARM:
                sb.append("- You are a Dynamic Router. Agents operate in a peer-to-peer relay fashion.\n");
                sb.append("- Based on the previous Agent's result, determine who is best suited to handle the next 'baton'.\n");
                sb.append("- Example: After A completes, if result is technical, pass to tech expert; if design-related, pass to designer");
                break;
            case CONTRACT_NET:
                sb.append("- Follow the 'Bidding-Awarding' protocol. If proposals haven't been gathered yet, you MUST output 'BIDDING'.\n");
                sb.append("- After receiving bids from Agents, compare their approaches and select one winner to execute.\n");
                sb.append("- Bidding triggers: When multiple approaches are needed, or uncertain about best fit\n");
                sb.append("- Award criteria: Capability match, proposal quality, execution efficiency");
                break;
            case BLACKBOARD:
                sb.append("- The history is a public Blackboard. Check for missing information or logical gaps on the board.\n");
                sb.append("- Assign an Agent who can fill these gaps or correct errors.\n");
                sb.append("- Common gap types: Missing facts, logical holes, inconsistent data, information needing verification\n");
                sb.append("- Assignment strategy: Select domain experts based on gap type");
                break;
            case MARKET_BASED:
                sb.append("- Every agent is an independent service provider. Consider efficiency and expertise.\n");
                sb.append("- Select the Agent who can resolve the current issue with the fewest steps and highest quality.\n");
                sb.append("- Evaluation dimensions: Expertise match, historical performance, execution efficiency, cost-effectiveness\n");
                sb.append("- Market principle: Competition-driven, survival of the fittest");
                break;
            default:
                sb.append("- As team mediator, make optimal decisions based on task requirements and member capabilities");
                break;
        }
    }
}