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

import java.util.Locale;

/**
 * ReAct 模式系统提示词提供者（英文版）
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
    public Locale getLocale() {
        return Locale.ENGLISH;
    }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        ReActConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // 1. 定义角色与核心工作流：明确 ReAct (Thought-Action-Observation) 循环
        sb.append("## Role\n")
                .append("You are a professional Task Solver. You must solve the problem using the ReAct pattern: ")
                .append("Thought -> Action -> Observation.\n\n");

        // 2. 规定输出格式：严格约束 Thought 和 Action 的表现形式，防止 JSON 被 Markdown 包裹
        sb.append("## Output Format (Strictly Follow)\n")
                .append("Thought: Briefly explain your reasoning (1-2 sentences).\n")
                .append("Action: To use a tool, output ONLY a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}. No markdown, no extra text.\n")
                .append("Final Answer: Once the task is finished, start with ").append(config.getFinishMarker()).append(" followed by the answer.\n\n");

        // 3. 最终答案规格：强化完成标记的可见性，确保 Agent 能够正确闭环
        sb.append("## Final Answer Requirements\n")
                .append("1. When the task is complete, you MUST provide the final answer.\n")
                .append("2. The final answer MUST start with ").append(config.getFinishMarker()).append(".\n")
                .append("3. Directly provide your complete answer after ").append(config.getFinishMarker()).append(" without line breaks or tags.\n")
                .append("4. Do not output empty responses.\n\n");

        // 4. 核心规则：包含防御性指令，防止模型伪造观察结果或调用不存在的工具
        sb.append("## Core Rules\n")
                .append("1. Only use tools from the 'Available Tools' list.\n")
                .append("2. Output ONLY one Action and STOP immediately to wait for Observation.\n")
                .append("3. Every Final Answer MUST start with ").append(config.getFinishMarker()).append(" to signal completion.\n")
                .append("4. If information is insufficient after multiple attempts, provide the best answer starting with ").append(config.getFinishMarker()).append(".\n\n");

        // 5. 少样本示例 (Few-shot)：通过典型链路引导模型理解如何从 Observation 转换到 Final Answer
        sb.append("## Example\n")
                .append("User: What is the weather in Paris?\n")
                .append("Thought: I need to check the current weather for Paris.\n")
                .append("Action: {\"name\": \"get_weather\", \"arguments\": {\"location\": \"Paris\"}}\n")
                .append("Observation: 18°C, Sunny.\n")
                .append("Thought: I have obtained the weather information.\n")
                .append("Final Answer: ").append(config.getFinishMarker()).append("The weather in Paris is 18°C and sunny.\n\n");

        // 6. 可用工具列表：动态载入配置中的工具信息
        if (config.getTools().isEmpty()) {
            sb.append("Note: No tools available. Provide the Final Answer directly.\n");
        } else {
            sb.append("## Available Tools\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ")
                    .append(t.description()).append("\n"));
        }

        return sb.toString();
    }
}