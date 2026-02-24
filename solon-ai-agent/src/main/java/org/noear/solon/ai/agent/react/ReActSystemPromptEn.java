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

    /**
     * 默认英文模板单例
     */
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
        String role = getRole(trace);
        String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        // 1. Role & Paradigm
        sb.append("## Your Role\n")
                .append(role).append(". ");

        if (trace.getConfig().getStyle() == ReActStyle.STRUCTURED_TEXT) {
            sb.append("You MUST use the ReAct pattern to solve the problem, ensuring each turn contains explicit labels: ")
                    .append("Thought -> Action -> Observation.\n\n");
        } else {
            sb.append("You MUST follow the ReAct (Reasoning and Acting) logic to solve the problem: ")
                    .append("perform a Thought, followed by an Action, and iterate based on the Observation.\n\n");
        }

        // 2. Instructions
        sb.append(instruction);

        return sb.toString();
    }

    public String getRole(ReActTrace trace) {
        if (roleDesc != null) {
            return roleDesc;
        }

        if (trace.getConfig().getRole() != null) {
            return trace.getConfig().getRole();
        }

        return "Professional task expert with autonomous action capabilities";
    }

    public String getInstruction(ReActTrace trace) {
        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            return getNaturalInstruction(trace);
        } else {
            return getClassicInstruction(trace); // 即你原来的逻辑
        }
    }

    protected String getNaturalInstruction(ReActTrace trace) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Code of Conduct\n")
                .append("1. **Tool Invocation**: If a tool needs to be called, trigger Function Calling 【directly】.\n")
                .append("2. **Direct Response**: Provide your answer directly after task completion. Do NOT output labels like 'Thought:' or 'Final Answer:'.\n")
                .append("3. **No Fabrication**: Strictly forbidden to simulate tool execution processes or forge return results.\n\n");

        // 业务指令注入
        appendBusinessInstructions(sb, trace);

        sb.append("## Example\n")
                .append("User: Check the weather in London and summarize it.\n")
                .append("(Model triggers function call: get_weather)\n")
                .append("(Model responds based on result)\n")
                .append("It is currently sunny in London with a temperature of 20°C, perfect for outdoor activities.\n\n");

        return sb.toString();
    }

    protected String getClassicInstruction(ReActTrace trace) {
        ReActAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. Format constraints
        sb.append("## Output Format (Strictly Follow)\n")
                .append("Thought: Briefly explain your reasoning (1-2 sentences).\n")
                .append("Action: To use a tool, you MUST output a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}. No markdown code blocks, no extra text.\n")
                .append("Final Answer: Once the task is finished, start with ").append(config.getFinishMarker()).append(" followed by the answer.\n\n");

        // B. Completion specs
        sb.append("## Final Answer Requirements\n")
                .append("1. When the task is complete, you MUST provide the final answer.\n")
                .append("2. The final answer MUST start with ").append(config.getFinishMarker()).append(".\n")
                .append("3. Directly provide your complete answer after ").append(config.getFinishMarker()).append(" without extra tags.\n\n");

        // C. Core behaviors
        sb.append("## Core Rules\n")
                .append("1. Only use tools from the 'Available Tools' list.\n")
                .append("2. Output ONLY one Action and STOP immediately to wait for Observation.\n")
                .append("3. Completion is signaled ONLY by ").append(config.getFinishMarker()).append(".\n\n");

        // D. Business instructions
        appendBusinessInstructions(sb, trace);

        // E. Few-shot guidance
        sb.append("## Example\n")
                .append("User: What is the weather in Paris?\n")
                .append("Thought: I need to check the current weather for Paris.\n")
                .append("Action: {\"name\": \"get_weather\", \"arguments\": {\"location\": \"Paris\"}}\n")
                .append("Thought: I have obtained the weather information.\n")
                .append("Final Answer: ").append(config.getFinishMarker()).append("The weather in Paris is 18°C and sunny.\n");


        // F. Toolset
        if (trace.getOptions().getTools().isEmpty()) {
            sb.append("\nNote: No tools available. Provide the Final Answer directly.\n");
        } else {
            sb.append("\n## Available Tools\n");
            sb.append("You can invoke the following tools via the Action field:\n");
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

    private void appendBusinessInstructions(StringBuilder sb, ReActTrace trace) {
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements ReActSystemPrompt.Builder {
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