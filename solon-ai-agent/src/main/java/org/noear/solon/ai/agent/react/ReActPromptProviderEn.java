/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
 * Aligned with the 'Trace' concept for better logical consistency.
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActPromptProviderEn implements ReActPromptProvider {
    private static final ReActPromptProvider instance = new ReActPromptProviderEn();

    public static ReActPromptProvider getInstance() {
        return instance;
    }

    @Override
    public String getSystemPrompt(ReActConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant solving complex problems by maintaining a **Reasoning Trace**.\n");
        sb.append("Strictly follow the ReAct protocol to update the trace step by step:\n\n");

        sb.append("Thought: Briefly explain your reasoning based on the current status of the trace.\n");

        if (!config.getTools().isEmpty()) {
            sb.append("Action: To use a tool, output a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}\n");
            sb.append("   - Example: {\"name\": \"get_order\", \"arguments\": {\"id\": \"123\"}}\n");
        } else {
            sb.append("Action: 【IMPORTANT】No tools are currently available. DO NOT attempt to call any tools.\n");
            sb.append("   If the user requests tool usage, directly use ").append(config.getFinishMarker())
                    .append(" to inform them that this service is currently unavailable.\n");
        }

        sb.append("Observation: System feedback will be provided here. Do NOT write this yourself.\n\n");

        sb.append("Once you have the final answer, you MUST use: ").append(config.getFinishMarker())
                .append(" followed by your complete response.\n\n");

        if (!config.getTools().isEmpty()) {
            sb.append("## Available Tools (ONLY these tools are allowed):\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
            sb.append("\n## Critical Constraints:\n");
            sb.append("1. **Trace Consistency**: Every step must logically follow from the previous footprints in the trace.\n");
            sb.append("2. **One Action Only**: Never issue multiple tool calls in a single response.\n");
            sb.append("3. **Stop Sequences**: Stop immediately after the Action JSON. Wait for the Observation.\n");
            sb.append("4. **No Hallucination**: Never hallucinate or manufacture the 'Observation:' content.\n");
            sb.append("5. **Final Answer**: If the goal is reached or no tools are needed, provide the answer using ").append(config.getFinishMarker()).append(".");
        } else {
            sb.append("## Important Notes:\n");
            sb.append("1. **No tools available**: DO NOT attempt to call any tools.\n");
            sb.append("2. **Direct answering**: Answer user questions directly based on your knowledge and reasoning.\n");
            sb.append("3. **No hallucination**: If you don't know the answer, honestly tell the user.\n");
            sb.append("4. **Use finish marker**: When completing your answer, start with '").append(config.getFinishMarker()).append("'.");
        }

        return sb.toString();
    }
}