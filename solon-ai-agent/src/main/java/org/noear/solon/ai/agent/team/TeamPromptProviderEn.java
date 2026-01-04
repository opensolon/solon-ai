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

import org.noear.solon.lang.Preview;
import java.util.Locale;

/**
 * Team Collaboration System Prompt Provider (English Version)
 * * <p>This provider guides the Team Supervisor to coordinate multiple agents.
 * It ensures the execution flow follows the designated protocol and maintains
 * the integrity of the final output through strict formatting rules.</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamPromptProviderEn implements TeamPromptProvider {
    private static final TeamPromptProviderEn INSTANCE = new TeamPromptProviderEn();
    public static TeamPromptProviderEn getInstance() { return INSTANCE; }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        TeamConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // 1. Role and Team Members List
        sb.append("## Role Definition\n");
        sb.append("You are the Team Supervisor, responsible for coordinating the following agents to complete the task:\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- **").append(name).append("**: ").append(agent.description()).append("\n");
        });

        // 2. Task Context
        sb.append("\n## Current Task\n").append(trace.getPrompt().getUserContent()).append("\n");

        // 3. Collaboration Protocol: Strategy-specific instructions
        sb.append("\n## Collaboration Protocol: ").append(config.getProtocol().name()).append("\n");
        config.getProtocol().injectInstruction(config, Locale.ENGLISH, sb);

        // 4. Output Specification: Strict constraints on response format
        sb.append("\n## Output Specification\n");
        sb.append("1. **Progress Analysis**: Evaluate current progress and determine the next logical step.\n");
        sb.append("2. **Termination**: If the task is finished, output: ").append(config.getFinishMarker())
                .append(" followed by the **complete and verbatim** result from the last executive Agent. DO NOT summarize or paraphrase.\n");
        sb.append("3. **Routing**: Otherwise, output **ONLY** the name of the next Agent to execute with no extra text.\n");

        // 5. History Analysis Logic
        sb.append("\n## History Analysis\n");
        sb.append("Analyze the collaboration history. If the history contains sufficient information to solve the user's request, provide the final answer immediately.\n");

        // 6. Termination Constraints
        sb.append("\n## Termination Conditions\n");
        sb.append("- Completion Signal: ").append(config.getFinishMarker()).append(".\n");
        sb.append("- Note: Avoid premature termination. Ensure all necessary experts have contributed to the final result.");

        return sb.toString();
    }
}