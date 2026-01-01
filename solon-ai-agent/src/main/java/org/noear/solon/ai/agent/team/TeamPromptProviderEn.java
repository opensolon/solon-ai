/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * English Prompt Provider (Supports all TeamStrategy protocols) - Test Stable Version
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
        sb.append("You are the Team Supervisor, responsible for coordinating the following agents to complete the task:\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- ").append(name).append(": ").append(agent.description()).append("\n");
        });

        sb.append("\nCurrent Task: ").append(prompt.getUserContent()).append("\n");

        sb.append("\nCollaboration Protocol: ").append(config.getStrategy()).append("\n");
        injectStrategyInstruction(sb, config.getStrategy(), config.getFinishMarker());

        sb.append("\n### Output Specification\n");
        sb.append("1. Analyze current progress and decide the next action\n");
        sb.append("2. If the task is completed, output: ").append(config.getFinishMarker()).append(" Final answer\n");
        sb.append("3. Otherwise, output ONLY the name of the NEXT Agent to execute\n");

        // Simplified history analysis
        sb.append("\n### History Analysis\n");
        sb.append("You will receive collaboration history. If history contains enough information to answer the question, provide final answer directly.\n");

        // Simplified termination conditions
        sb.append("\n### Termination\n");
        sb.append("Output ").append(config.getFinishMarker()).append(" when task is done.\n");
        sb.append("Note: Don't terminate too early. Ensure necessary experts have a chance to contribute.");

        return sb.toString();
    }

    private void injectStrategyInstruction(StringBuilder sb, TeamStrategy strategy, String finishMarker) {
        switch (strategy) {
            case SEQUENTIAL:
                sb.append("- Collaboration Protocol: Sequential Pipeline Mode.\n");
                sb.append("- Tasks are assigned in the predefined order. Ends after all experts have executed.");
                break;
            case HIERARCHICAL:
                sb.append("- You are the Lead Supervisor. Decompose the task into steps and assign suitable Agents.\n");
                sb.append("- Review each member's output to ensure it meets requirements.");
                break;
            case SWARM:
                sb.append("- You are a Dynamic Router. Agents operate in peer-to-peer relay fashion.\n");
                sb.append("- Based on previous Agent's result, determine who is best for the next 'baton'.");
                break;
            case CONTRACT_NET:
                sb.append("- Follow 'Bidding-Awarding' protocol. If multiple approaches needed, output 'BIDDING' first.\n");
                sb.append("- After receiving bids, compare approaches and select one winner to execute.");
                break;
            case BLACKBOARD:
                sb.append("- History is a public Blackboard. Check for missing information on the board.\n");
                sb.append("- Assign an Agent who can fill gaps or correct errors.");
                break;
            case MARKET_BASED:
                sb.append("- Every agent is an independent service provider. Consider efficiency and expertise.\n");
                sb.append("- Select the Agent who can resolve the issue with fewest steps and highest quality.");
                break;
            default:
                sb.append("- As team supervisor, make decisions based on task requirements and member capabilities.");
                break;
        }
    }
}