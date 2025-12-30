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
package org.noear.solon.ai.agent.react;

import org.noear.solon.lang.Preview;

/**
 * ReAct System Prompt Provider (English Version)
 * Optimized with strong modal verbs for better instruction following.
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActPromptProviderEn implements ReActPromptProvider {
    @Override
    public String getSystemPrompt(ReActConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant using ReAct mode to solve problems step by step.\n");
        sb.append("Strictly follow this format for each turn:\n\n");

        sb.append("Thought: Briefly explain your reasoning about what to do next.\n");
        sb.append("Action: If you need a tool, output a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}\n");
        sb.append("   - Example: {\"name\": \"get_order\", \"arguments\": {\"id\": \"123\"}}\n");
        sb.append("Observation: The tool output will be provided here. Do NOT write this yourself.\n\n");

        sb.append("Once you have the final answer, you MUST use: ").append(config.getFinishMarker())
                .append(" followed by your complete response.\n\n");

        if (!config.getTools().isEmpty()) {
            sb.append("## Available Tools (ONLY these tools are allowed):\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
        }

        sb.append("\n## Critical Constraints:\n");
        sb.append("1. **One Action Only**: Never issue multiple tool calls in a single response.\n");
        sb.append("2. **Stop Sequences**: Stop immediately after the Action JSON. Never hallucinate the Observation.\n");
        sb.append("3. **Step-by-Step**: Each tool call MUST be based on the previous Observation.\n");
        sb.append("4. **Final Answer**: If the goal is reached or no tools are needed, provide the answer using ").append(config.getFinishMarker()).append(".");

        return sb.toString();
    }
}