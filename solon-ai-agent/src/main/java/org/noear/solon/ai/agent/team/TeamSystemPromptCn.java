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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.lang.Preview;
import java.util.Locale;
import java.util.function.Function;

/**
 * Team 协作模式系统提示词实现（中文版）
 *
 * <p>该类负责为团队主管 (Supervisor) 生成系统提示词。核心逻辑包括：</p>
 * <ul>
 * <li>1. <b>成员调度</b>：根据成员职责描述，决定任务在 Agent 间的流转。</li>
 * <li>2. <b>协议对齐</b>：注入具体的协作协议指令（如顺序执行、自主路由等）。</li>
 * <li>3. <b>输出控制</b>：通过严格的规范约束，确保主管仅输出决策指令或最终结果。</li>
 * </ul>
 *
 * <p>支持增量构建模式：可自定义 Role (角色) 和核心业务 Instruction (指令)。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamSystemPromptCn implements TeamSystemPrompt {
    /**
     * 默认单例（使用标准主管模板）
     */
    private static final TeamSystemPrompt _DEFAULT = new TeamSystemPromptCn(null, null);

    public static TeamSystemPrompt getDefault() { return _DEFAULT; }

    private final Function<TeamTrace, String> roleProvider;
    private final Function<TeamTrace, String> instructionProvider;

    protected TeamSystemPromptCn(Function<TeamTrace, String> roleProvider,
                                 Function<TeamTrace, String> instructionProvider) {
        this.roleProvider = roleProvider;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() {
        return Locale.CHINESE;
    }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色定义片段
        sb.append("## 角色定义\n").append(getRole(trace)).append("\n\n");

        // 2. 综合指令注入 (包含成员名录、任务上下文、协作协议及业务规则)
        sb.append(getInstruction(trace));

        return sb.toString();
    }

    @Override
    public String getRole(TeamTrace trace) {
        // 优先使用自定义的角色提供者
        if (roleProvider != null) {
            return roleProvider.apply(trace);
        }
        return "你是一个团队协作主管 (Supervisor)，负责协调成员完成任务";
    }

    @Override
    public String getInstruction(TeamTrace trace) {
        TeamAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 团队成员：动态注入当前团队中各 Agent 的职责描述
        sb.append("### 团队成员\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- **").append(name).append("**:\n");
            sb.append("  - 职责: ").append(agent.descriptionFor(trace.getContext())).append("\n");

            AgentProfile profile = agent.profile();
            if (profile != null) {
                String info = profile.toFormatString(getLocale());
                if (info.length() > 0) {
                    // 使用行内代码块包裹，并增加醒目的标签
                    sb.append("  - 契约: `").append(info).append("`\n");
                }
            }
        });

        // B. 任务上下文：明确当前原始需求
        sb.append("\n### 当前任务\n").append(trace.getPrompt().getUserContent()).append("\n");

        // C. 协作协议：由具体的执行策略（如 Sequential）注入特定流转指令
        sb.append("\n### 协作协议\n");
        config.getProtocol().injectSupervisorInstruction(Locale.CHINESE, sb);

        // D. 输出规范：强制约束回复格式，确保系统可自动化解析决策
        sb.append("\n### 输出规范\n")
                .append("1. **状态分析**：分析当前执行进度，决定下一步行动。\n")
                .append("2. **任务终止**：如果任务已完成，必须输出: ").append(config.getFinishMarker())
                .append(" 并在其后提供最终答案。\n")
                .append("3. **继续执行**：若任务未完成，请**仅输出**下一个要执行的 Agent 名字，不要有额外文本。\n");

        // E. 决策准则：防止死循环及确保协作深度
        sb.append("\n### 历史分析与准则\n")
                .append("- 参考协作历史。如果历史记录已足以回答问题，立即结束并输出最终答案。\n")
                .append("- 任务完成信号：").append(config.getFinishMarker()).append("。\n")
                .append("- 注意：严禁过早结束，确保必要的专家已参与决策。\n");

        // F. 增量业务指令：追加用户自定义的特定业务约束
        if (instructionProvider != null) {
            sb.append("\n### 核心任务指令\n");
            sb.append(instructionProvider.apply(trace)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取构建器
     */
    public static Builder builder() { return new Builder(); }

    /**
     * TeamSystemPromptCn 构建器
     */
    public static class Builder implements TeamSystemPrompt.Builder{
        private Function<TeamTrace, String> roleProvider;
        private Function<TeamTrace, String> instructionProvider;

        /**
         * 设置角色描述片段
         */
        public Builder role(String role) {
            this.roleProvider = (t) -> role;
            return this;
        }

        /**
         * 设置角色描述逻辑（支持动态生成）
         */
        public Builder role(Function<TeamTrace, String> roleProvider) {
            this.roleProvider = roleProvider;
            return this;
        }

        /**
         * 设置核心业务指令（将作为“核心任务指令”增量追加）
         */
        public Builder instruction(String instruction) {
            this.instructionProvider = (t) -> instruction;
            return this;
        }

        /**
         * 设置核心业务指令逻辑（支持动态生成）
         */
        public Builder instruction(Function<TeamTrace, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        /**
         * 构建 TeamSystemPrompt 实例
         */
        public TeamSystemPrompt build() {
            return new TeamSystemPromptCn(roleProvider, instructionProvider);
        }
    }
}