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

import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.function.Function;

/**
 * ReAct 模式提示词提供者（英文版）
 *
 * <p>该实现采用“协议+业务”增量构建模式：</p>
 * <ul>
 * <li>Role: 优先使用自定义角色，并自动叠加英文环境下的 ReAct 范式定义。</li>
 * <li>Instruction: 强制注入 ReAct 标准输出协议（英文），并将自定义业务指令作为“Core Task Instructions”增量追加。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActSystemPromptEn implements ReActSystemPrompt {
    /**
     * 默认单例（使用标准英文 ReAct 模板）
     */
    private static final ReActSystemPrompt _DEFAULT = new ReActSystemPromptEn(null, null);

    public static ReActSystemPrompt getDefault() {
        return _DEFAULT;
    }

    private final Function<ReActTrace, String> roleProvider;
    private final Function<ReActTrace, String> instructionProvider;

    protected ReActSystemPromptEn(Function<ReActTrace, String> roleProvider,
                                  Function<ReActTrace, String> instructionProvider) {
        this.roleProvider = roleProvider;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() {
        return Locale.ENGLISH;
    }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        final String role = getRole(trace);
        final String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        // 1. 角色与范式定义：明确 ReAct (Reasoning and Acting) 循环要求
        sb.append("## Role\n")
                .append(role).append(". ")
                .append("You must solve the problem using the ReAct pattern: ")
                .append("Thought -> Action -> Observation.\n\n");

        // 2. 注入综合指令 (包含输出格式、核心规则、业务任务与示例)
        sb.append(instruction);

        // 3. 工具集定义：动态注入当前可用的工具列表
        if (trace.getConfig().getTools().isEmpty()) {
            sb.append("\nNote: No tools available. Provide the Final Answer directly.\n");
        } else {
            sb.append("\n## Available Tools\n");
            trace.getConfig().getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ")
                    .append(t.description()).append("\n"));
        }

        return sb.toString();
    }

    @Override
    public String getRole(ReActTrace trace) {
        // 增量逻辑：优先返回自定义角色描述，否则返回默认专业助手描述
        if (roleProvider != null) {
            return roleProvider.apply(trace);
        }
        return "You are a professional Task Solver";
    }

    @Override
    public String getInstruction(ReActTrace trace) {
        ReActConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 输出格式约束（ReAct 协议基石）
        sb.append("## Output Format (Strictly Follow)\n")
                .append("Thought: Briefly explain your reasoning (1-2 sentences).\n")
                .append("Action: To use a tool, output ONLY a single JSON object: {\"name\": \"tool_name\", \"arguments\": {...}}. No markdown, no extra text.\n")
                .append("Final Answer: Once the task is finished, start with ").append(config.getFinishMarker()).append(" followed by the answer.\n\n");

        // B. 最终答案规格
        sb.append("## Final Answer Requirements\n")
                .append("1. When the task is complete, you MUST provide the final answer.\n")
                .append("2. The final answer MUST start with ").append(config.getFinishMarker()).append(".\n")
                .append("3. Directly provide your complete answer after ").append(config.getFinishMarker()).append(" without extra tags.\n\n");

        // C. 核心行为准则
        sb.append("## Core Rules\n")
                .append("1. Only use tools from the 'Available Tools' list.\n")
                .append("2. Output ONLY one Action and STOP immediately to wait for Observation.\n")
                .append("3. Every Final Answer MUST start with ").append(config.getFinishMarker()).append(" to signal completion.\n\n");

        // --- 增量业务指令注入 ---
        if (instructionProvider != null) {
            sb.append("## Core Task Instructions\n");
            sb.append(instructionProvider.apply(trace)).append("\n\n");
        }

        // D. 示例引导 (Few-shot 模仿学习)
        sb.append("## Example\n")
                .append("User: What is the weather in Paris?\n")
                .append("Thought: I need to check the current weather for Paris.\n")
                .append("Action: {\"name\": \"get_weather\", \"arguments\": {\"location\": \"Paris\"}}\n")
                .append("Observation: 18°C, Sunny.\n")
                .append("Thought: I have obtained the weather information.\n")
                .append("Final Answer: ").append(config.getFinishMarker()).append("The weather in Paris is 18°C and sunny.\n");

        return sb.toString();
    }

    /**
     * 创建提供者构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构建器
     */
    public static class Builder implements ReActSystemPrompt.Builder{
        private Function<ReActTrace, String> roleProvider;
        private Function<ReActTrace, String> instructionProvider;

        public Builder() {
            this.roleProvider = null;
            this.instructionProvider = null;
        }

        /**
         * 设置角色描述（静态字符串）
         */
        public Builder role(String role) {
            this.roleProvider = (trace) -> role;
            return this;
        }

        /**
         * 设置角色提供逻辑（动态函数）
         */
        public Builder role(Function<ReActTrace, String> roleProvider) {
            this.roleProvider = roleProvider;
            return this;
        }

        /**
         * 设置指令描述（静态字符串，作为核心任务指令增量追加）
         */
        public Builder instruction(String instruction) {
            this.instructionProvider = (trace) -> instruction;
            return this;
        }

        /**
         * 设置指令提供逻辑（动态函数，作为核心任务指令增量追加）
         */
        public Builder instruction(Function<ReActTrace, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        /**
         * 构建提供者实例
         */
        public ReActSystemPrompt build() {
            return new ReActSystemPromptEn(roleProvider, instructionProvider);
        }
    }
}