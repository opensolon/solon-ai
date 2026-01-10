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
package org.noear.solon.ai.agent.team;

import org.noear.solon.lang.Preview;
import java.util.Locale;
import java.util.function.Function;

/**
 * Team 协作模式系统提示词提供者（中文版）
 *
 * <p>该类负责为团队主管 (Supervisor) 生成系统提示词。其核心逻辑在于：</p>
 * <ul>
 * <li>1. 协调多个 Agent 成员，根据协作协议 (Protocol) 决定任务流转或终止。</li>
 * <li>2. 维护团队成员的名录与职责上下文。</li>
 * <li>3. 强制执行严格的输出规范，确保 Supervisor 只做决策路由。</li>
 * </ul>
 *
 * <p>该实现采用“协议+业务”分段构建模式：</p>
 * <ul>
 * <li>Role: 优先使用自定义角色描述，默认为团队协作主管。</li>
 * <li>Instruction: 强制注入成员列表、协作协议和输出规范，并将自定义指令作为“核心任务指令”追加。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamPromptProviderCn implements TeamPromptProvider {
    /**
     * 默认单例（使用标准主管模板）
     */
    private static final TeamPromptProviderCn _DEFAULT = new TeamPromptProviderCn(null, null);

    public static TeamPromptProviderCn getInstance() { return _DEFAULT; }

    private final Function<TeamTrace, String> roleProvider;
    private final Function<TeamTrace, String> instructionProvider;

    protected TeamPromptProviderCn(Function<TeamTrace, String> roleProvider,
                                   Function<TeamTrace, String> instructionProvider) {
        this.roleProvider = roleProvider;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色定义
        sb.append("## 角色定义\n").append(getRole(trace)).append("\n\n");

        // 2. 指令与规范注入 (包含成员、上下文、协议及业务指令)
        sb.append(getInstruction(trace));

        return sb.toString();
    }

    @Override
    public String getRole(TeamTrace trace) {
        // 优先返回自定义角色描述
        if (roleProvider != null) {
            return roleProvider.apply(trace);
        }
        return "你是一个团队协作主管 (Supervisor)，负责协调成员完成任务";
    }

    @Override
    public String getInstruction(TeamTrace trace) {
        TeamConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 角色定义与团队成员列表：动态注入当前可用的 Agent 列表
        sb.append("### 团队成员\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- **").append(name).append("**: ").append(agent.descriptionFor(trace.getContext())).append("\n");
        });

        // B. 任务上下文：明确当前处理的原始需求
        sb.append("\n### 当前任务\n").append(trace.getPrompt().getUserContent()).append("\n");

        // C. 协作协议指令：由具体协议（如 Sequential, Market）注入特定的流转逻辑
        sb.append("\n### 协作协议\n");
        config.getProtocol().injectSupervisorInstruction(Locale.CHINESE, sb);

        // D. 输出规范：严格约束 Supervisor 的响应格式，确保系统可解析
        sb.append("\n### 输出规范\n")
                .append("1. **状态分析**：分析当前执行进度，决定下一步行动。\n")
                .append("2. **任务终止**：如果任务已完成，必须输出: ").append(config.getFinishMarker())
                .append(" 并在其后提供最终答案。\n")
                .append("3. **继续执行**：若任务未完成，请**仅输出**下一个要执行的 Agent 名字，不要有额外文本。\n");

        // E. 历史记录与准则：防止死循环并确保决策质量
        sb.append("\n### 历史分析与准则\n")
                .append("- 参考协作历史。如果历史记录已足以回答问题，立即结束并输出最终答案。\n")
                .append("- 任务完成信号：").append(config.getFinishMarker()).append("。\n")
                .append("- 注意：严禁过早结束，确保必要的专家已参与决策。\n");

        // F. 增量业务指令注入
        if (instructionProvider != null) {
            sb.append("\n### 核心任务指令\n");
            sb.append(instructionProvider.apply(trace)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 创建提供者构建器
     */
    public static Builder builder() { return new Builder(); }

    /**
     * 构建器
     */
    public static class Builder {
        private Function<TeamTrace, String> roleProvider;
        private Function<TeamTrace, String> instructionProvider;

        /**
         * 设置角色描述（静态字符串）
         */
        public Builder role(String role) { this.roleProvider = (t) -> role; return this; }

        /**
         * 设置角色描述（静态字符串）
         */
        public Builder role(Function<TeamTrace, String> roleProvider) { this.roleProvider = roleProvider; return this; }

        /**
         * 设置指令描述（静态字符串，作为核心任务指令增量追加）
         */
        public Builder instruction(String instruction) { this.instructionProvider = (t) -> instruction; return this; }


        /**
         * 设置指令描述（静态字符串，作为核心任务指令增量追加）
         */
        public Builder instruction(Function<TeamTrace, String> instructionProvider) { this.instructionProvider = instructionProvider; return this; }

        /**
         * 构建提供者实例
         */
        public TeamPromptProviderCn build() { return new TeamPromptProviderCn(roleProvider, instructionProvider); }
    }
}