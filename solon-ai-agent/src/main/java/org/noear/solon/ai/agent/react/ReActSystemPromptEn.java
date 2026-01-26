/*
 * Copyright 2017-2026 noear.org and authors
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

import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Function;

/**
 * ReAct 模式提示词提供者（英文版）
 * <p>基于 Reasoning-Acting 范式，强制注入英文协议约束，支持业务指令增量扩展。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActSystemPromptEn implements ReActSystemPrompt {
    private static final Logger log = LoggerFactory.getLogger(ReActSystemPromptEn.class);

    /** 默认英文模板单例 */
    private static final ReActSystemPrompt _DEFAULT = new ReActSystemPromptEn(null, null);

    public static ReActSystemPrompt getDefault() {
        return _DEFAULT;
    }

    private final String roleDesc;
    private final Function<ReActTrace, String> instructionProvider;

    protected ReActSystemPromptEn(String roleDesc,
                                  Function<ReActTrace, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() {
        return Locale.ENGLISH;
    }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        final String role = getRole();
        final String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        // 1. Role & Paradigm
        sb.append("## Role\n")
                .append(role).append(". ")
                .append("You must solve the problem using the ReAct pattern: ")
                .append("Thought -> Action -> Observation.\n\n");

        // 2. Instructions
        sb.append(instruction);

        // 3. Toolset
        if (trace.getOptions().getTools().isEmpty()) {
            sb.append("\nNote: No tools available. Provide the Final Answer directly.\n");
        } else {
            sb.append("\n## Available Tools\n");
            // 同步中文版：明确使用内置函数调用
            sb.append("You can also use the following tools, preferably via the model's built-in Function Calling feature:\n");
            trace.getOptions().getTools().forEach(t -> {
                sb.append("- ").append(t.name()).append(": ").append(t.descriptionAndMeta());
                if (Assert.isNotEmpty(t.inputSchema())) {
                    sb.append(" Input Schema: ").append(t.inputSchema());
                }
                sb.append("\n");
            });
        }

        return sb.toString();
    }

    @Override
    public String getRole() {
        if (roleDesc != null) {
            return roleDesc;
        }
        return "You are a professional Task Solver";
    }

    @Override
    public String getInstruction(ReActTrace trace) {
        ReActAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. Format constraints
        sb.append("## Output Format (Strictly Follow)\n")
                .append("Thought: Briefly explain your reasoning (1-2 sentences).\n")
                .append("Action: To use a tool, prioritize using the model's built-in Function Calling tool. If the environment does not support it, output ONLY a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}. No markdown, no extra text.\n")
                .append("Final Answer: Once the task is finished, start with ").append(config.getFinishMarker()).append(" followed by the answer.\n\n");

        // B. Completion specs
        sb.append("## Final Answer Requirements\n")
                .append("1. When the task is complete, you MUST provide the final answer.\n")
                .append("2. The final answer MUST start with ").append(config.getFinishMarker()).append(".\n")
                .append("3. Directly provide your complete answer after ").append(config.getFinishMarker()).append(" without extra tags.\n\n");

        // C. Core behaviors
        sb.append("## Core Rules\n")
                .append("1. Prioritize using the model's built-in Function Calling protocol.\n")
                .append("2. Only use tools from the 'Available Tools' list.\n")
                .append("3. Output ONLY one Action and STOP immediately to wait for Observation.\n")
                .append("4. Completion is signaled ONLY by ").append(config.getFinishMarker()).append(".\n\n");

        // D. Business instructions
        if (instructionProvider != null || trace.getOptions().getSkillInstruction() != null) {
            sb.append("## Core Task Instructions\n");

            // Agent-level instructions
            if (instructionProvider != null) {
                sb.append(instructionProvider.apply(trace)).append("\n");
            }

            // Skill-level instructions (Add a sub-header for better focus)
            if (trace.getOptions().getSkillInstruction() != null) {
                sb.append("### Supplemental Guidelines\n");
                sb.append(trace.getOptions().getSkillInstruction()).append("\n");
            }
            sb.append("\n");
        }

        // E. Few-shot guidance
        sb.append("## Example\n")
                .append("User: What is the weather in Paris?\n")
                .append("Thought: I need to check the current weather for Paris.\n")
                .append("Action: {\"name\": \"get_weather\", \"arguments\": {\"location\": \"Paris\"}}\n")
                .append("Thought: I have obtained the weather information.\n")
                .append("Final Answer: ").append(config.getFinishMarker()).append("The weather in Paris is 18°C and sunny.\n");

        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements ReActSystemPrompt.Builder{
        private String roleDesc;
        private Function<ReActTrace, String> instructionProvider;

        public Builder() {
            this.roleDesc = null;
            this.instructionProvider = null;
        }

        public Builder role(String role) {
            this.roleDesc = role;
            return this;
        }

        public Builder instruction(String instruction) {
            this.instructionProvider = (trace) -> instruction;
            return this;
        }

        public Builder instruction(Function<ReActTrace, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        public ReActSystemPrompt build() {
            if (log.isDebugEnabled()) {
                log.debug("Building ReActSystemPromptEn (Custom Role: {}, Custom Instruction: {})",
                        roleDesc != null, instructionProvider != null);
            }
            return new ReActSystemPromptEn(roleDesc, instructionProvider);
        }
    }
}