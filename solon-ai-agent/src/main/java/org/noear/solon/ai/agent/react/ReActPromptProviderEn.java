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
 * ReAct System Prompt Provider (English Version) - Optimized
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

        // Simplified role description
        sb.append("You are an AI assistant solving problems. Follow this format step by step:\n\n");

        sb.append("Thought: Briefly explain your reasoning (1-2 sentences)\n");

        if (!config.getTools().isEmpty()) {
            sb.append("Action: To use a tool, output a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}\n");
            sb.append("   - Example: {\"name\": \"get_order\", \"arguments\": {\"id\": \"123\"}}\n");
        } else {
            // Critical fix: Explicitly tell Agent MUST output finish marker
            sb.append("Action: 【IMPORTANT】No tools available. You MUST output the final answer directly.\n");
            sb.append("   Format requirement: Start with ").append(config.getFinishMarker()).append(", then your answer\n");
            sb.append("   Example: ").append(config.getFinishMarker()).append(" Here is the complete answer...\n");
        }

        sb.append("Observation: System will provide feedback (do NOT write this yourself)\n\n");

        // Strengthen final answer requirements
        sb.append("### Final Answer Requirements (MUST FOLLOW):\n");
        sb.append("1. When ready to give final answer, MUST start with ").append(config.getFinishMarker()).append("\n");
        sb.append("2. After ").append(config.getFinishMarker()).append(" directly provide your complete answer, no line break\n");
        sb.append("3. Answer must be complete, detailed, directly addressing user's question\n");
        sb.append("4. Do NOT output Thought/Action/Observation tags\n");
        sb.append("5. Do NOT output empty responses\n\n");

        // Guidance for common questions
        sb.append("### Common Question Guidance:\n");
        sb.append("- If user asks about travel planning (e.g., Lhasa itinerary), answer MUST contain destination name\n");
        sb.append("- If user asks about performance testing, provide specific testing methods and suggestions\n");
        sb.append("- If user asks technical questions, provide detailed technical explanations\n");
        sb.append("- If don't know answer, honestly say so, but still start with ").append(config.getFinishMarker()).append("\n");

        // Simplify tool list display
        if (!config.getTools().isEmpty()) {
            sb.append("\nAvailable Tools:\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
        }

        return sb.toString();
    }
}