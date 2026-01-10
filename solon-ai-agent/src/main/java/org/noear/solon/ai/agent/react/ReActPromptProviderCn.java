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

import java.util.Locale;
import java.util.function.Function;

/**
 * ReAct 模式提示词提供者（中文版）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActPromptProviderCn implements ReActPromptProvider {
    /**
     * 默认单例（使用标准 ReAct 模板）
     */
    private static final ReActPromptProvider _DEFAULT = new ReActPromptProviderCn(null, null);

    public static ReActPromptProvider getDefault() {
        return _DEFAULT;
    }

    private final Function<ReActTrace, String> roleProvider;
    private final Function<ReActTrace, String> instructionProvider;

    protected ReActPromptProviderCn(Function<ReActTrace, String> roleProvider,
                                    Function<ReActTrace, String> instructionProvider) {
        this.roleProvider = roleProvider;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() {
        return Locale.CHINESE;
    }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        ReActConfig config = trace.getConfig();

        // 确定角色定义 (优先使用动态 Provider)
        final String role;
        if (roleProvider != null) {
            role = roleProvider.apply(trace);
        } else {
            role = getRole(trace);
        }

        // 确定执行指令 (优先使用动态 Provider)
        final String instruction;
        if (instructionProvider != null) {
            instruction = instructionProvider.apply(trace);
        } else {
            instruction = getInstruction(trace);
        }

        StringBuilder sb = new StringBuilder();

        // 1. 角色与范式定义：明确 ReAct (Reasoning and Acting) 循环
        sb.append("## 角色\n")
                .append(role).append("。")
                .append("你必须使用 ReAct 模式解决问题：")
                .append("Thought（思考） -> Action（行动） -> Observation（观察）。\n\n");

        // 2. 注入指令 (输出格式、要求、核心规则与示例)
        sb.append(instruction);

        // 3. 工具集定义：动态注入当前可用的工具列表
        if (config.getTools().isEmpty()) {
            sb.append("\n注意：当前没有可用工具。请直接给出 Final Answer。\n");
        } else {
            sb.append("\n## 可用工具\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ")
                    .append(t.description()).append("\n"));
        }

        return sb.toString();
    }

    @Override
    public String getRole(ReActTrace trace) {
        return "你是一个专业的任务解决助手";
    }

    @Override
    public String getInstruction(ReActTrace trace) {
        ReActConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 输出格式约束
        sb.append("## 输出格式（必须遵守）\n")
                .append("Thought: 简要解释你的思考过程（1-2句话）。\n")
                .append("Action: 如果需要调用工具，请输出唯一的 JSON 对象：{\"name\": \"工具名\", \"arguments\": {...}}。不要使用代码块，不要有额外文本。\n")
                .append("Final Answer: 任务完成后，以 ").append(config.getFinishMarker()).append(" 开头给出回答。\n\n");

        // B. 最终答案规格
        sb.append("## 最终答案要求\n")
                .append("1. 当你获得结论或信息已足够时，必须给出最终答案。\n")
                .append("2. 最终答案**必须**以 ").append(config.getFinishMarker()).append(" 开头。\n")
                .append("3. 在 ").append(config.getFinishMarker()).append(" 之后直接提供完整回答，不要换行，不要输出 Thought/Action/Observation 标签。\n")
                .append("4. 即使不知道答案，也请诚实说明并以 ").append(config.getFinishMarker()).append(" 开头。\n\n");

        // C. 核心行为准则
        sb.append("## 核心规则\n")
                .append("1. 每次仅输出一个 Action，输出后立即停止等待 Observation。\n")
                .append("2. 严禁伪造 Observation，严禁调用‘可用工具’之外的工具。\n")
                .append("3. 最终回答必须以 ").append(config.getFinishMarker()).append(" 开头，否则系统无法识别任务完成。\n")
                .append("4. 如果多次尝试后信息仍不足，请提供包含 ").append(config.getFinishMarker()).append(" 的最佳回答。\n\n");

        // D. 示例引导 (Few-shot)
        sb.append("## 示例\n")
                .append("用户: 北京天气怎么样？\n")
                .append("Thought: 我需要查询北京当前的实时天气信息。\n")
                .append("Action: {\"name\": \"get_weather\", \"arguments\": {\"location\": \"北京\"}}\n")
                .append("Observation: 25°C，晴间多云。\n")
                .append("Thought: 根据观察结果，北京天气良好。\n")
                .append("Final Answer: ").append(config.getFinishMarker()).append("北京目前天气晴间多云，气温约 25°C。\n");

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
    public static class Builder {
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
         * 设置指令描述（静态字符串）
         */
        public Builder instruction(String instruction) {
            this.instructionProvider = (trace) -> instruction;
            return this;
        }

        /**
         * 设置指令提供逻辑（动态函数）
         */
        public Builder instruction(Function<ReActTrace, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        /**
         * 构建提供者实例
         */
        public ReActPromptProvider build() {
            return new ReActPromptProviderCn(roleProvider, instructionProvider);
        }
    }
}