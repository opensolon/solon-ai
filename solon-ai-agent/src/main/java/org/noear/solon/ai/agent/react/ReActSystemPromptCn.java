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
 * ReAct 模式提示词提供者（中文版）
 * <p>采用“协议+业务”增量构建：核心协议（Thought/Action/Observation）由内部强制注入，业务指令增量追加。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActSystemPromptCn implements ReActSystemPrompt {
    private static final Logger log = LoggerFactory.getLogger(ReActSystemPromptCn.class);

    /** 默认单例（标准 ReAct 模板） */
    private static final ReActSystemPrompt _DEFAULT = new ReActSystemPromptCn(null, null);

    public static ReActSystemPrompt getDefault() { return _DEFAULT; }

    private final String roleDesc;
    private final Function<ReActTrace, String> instructionProvider;

    protected ReActSystemPromptCn(String roleDesc,
                                  Function<ReActTrace, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() { return Locale.CHINESE; }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        final String role = getRole();
        final String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        // 1. 角色定义与 ReAct 范式宣告
        sb.append("## 角色\n")
                .append(role).append("。")
                .append("你必须使用 ReAct 模式解决问题：")
                .append("Thought（思考） -> Action（行动） -> Observation（观察）。\n\n");

        // 2. 注入指令集（含格式、准则、示例）
        sb.append(instruction);

        // 3. 工具集动态注入
        if (trace.getOptions().getTools().isEmpty()) {
            sb.append("\n注意：当前没有可用工具。请直接给出 Final Answer。\n");
        } else {
            sb.append("\n## 可用工具\n");
            sb.append("你也可以通过模型内置的函数调用工具（Function Calling）使用以下工具：\n");
            trace.getOptions().getTools().forEach(t -> {
                sb.append("- ").append(t.name()).append(": ").append(t.descriptionAndMeta());
                // 必须告知模型参数 Schema 以便生成正确的 JSON
                if (Assert.isNotEmpty(t.inputSchema())) {
                    sb.append(" 参数定义: ").append(t.inputSchema());
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
        return "你是一个专业的任务解决助手";
    }

    @Override
    public String getInstruction(ReActTrace trace) {
        ReActAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 格式约束：ReAct 循环的语法基石
        sb.append("## 输出格式（必须遵守）\n")
                .append("Thought: 简要解释你的思考过程（1-2句话）。\n")
                .append("Action: 如果需要调用工具。优先使用模型内置的函数调用工具（Function Calling），若环境不支持则输出 JSON 对象：{\"name\": \"工具名\", \"arguments\": {...}}。不要使用代码块，不要有额外文本。\n")
                .append("Final Answer: 任务完成后，以 ").append(config.getFinishMarker()).append(" 开头给出回答。\n\n");

        // B. 结束规格：确保任务能被系统正确识别截断
        sb.append("## 最终答案要求\n")
                .append("1. 当你获得结论或信息已足够时，必须给出最终答案。\n")
                .append("2. 最终答案**必须**以 ").append(config.getFinishMarker()).append(" 开头。\n")
                .append("3. 在 ").append(config.getFinishMarker()).append(" 之后直接提供完整回答，不要换行，不要输出标注标签。\n\n");

        // C. 行为准则：防止模型出现伪造 Observation 或死循环
        sb.append("## 核心规则\n")
                .append("1. 优先使用模型内置的函数调用工具（Function Calling）。\n")
                .append("2. 每次仅输出一个 Action，输出后立即停止等待 Observation。\n")
                .append("3. 严禁伪造 Observation，严禁调用‘可用工具’之外的工具。\n")
                .append("4. 最终回答未带上 ").append(config.getFinishMarker()).append(" 将被视为无效。\n\n");

        // D. 业务指令注入
        if (instructionProvider != null || trace.getOptions().getSkillInstruction() != null) {
            sb.append("## 核心任务指令\n");

            // Agent 级指令
            if (instructionProvider != null) {
                sb.append(instructionProvider.apply(trace)).append("\n");
            }

            // Skill 级指令（增加一个子标题，强化感知）
            if (trace.getOptions().getSkillInstruction() != null) {
                sb.append("### 补充业务准则\n");
                sb.append(trace.getOptions().getSkillInstruction()).append("\n");
            }
            sb.append("\n");
        }

        // E. 少样本引导 (Few-shot)
        sb.append("## 示例\n")
                .append("用户: 北京天气怎么样？\n")
                .append("Thought: 我需要查询北京当前的实时天气信息。\n")
                .append("Action: {\"name\": \"get_weather\", \"arguments\": {\"location\": \"北京\"}}\n")
                .append("Observation: 25°C，晴间多云。\n")
                .append("Thought: 根据观察结果，北京天气良好。\n")
                .append("Final Answer: ").append(config.getFinishMarker()).append("北京目前天气晴间多云，气温约 25°C。\n");

        return sb.toString();
    }

    /** 创建构建器 */
    public static Builder builder() { return new Builder(); }

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
                log.debug("Building ReActSystemPromptCn with custom role: {}, custom instruction: {}",
                        roleDesc != null, instructionProvider != null);
            }
            return new ReActSystemPromptCn(roleDesc, instructionProvider);
        }
    }
}