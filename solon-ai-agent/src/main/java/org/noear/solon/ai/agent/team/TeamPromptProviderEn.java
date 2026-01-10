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
 * Team 协作模式系统提示词提供者（英文版）
 *
 * <p>该类负责为团队主管 (Supervisor) 生成英文系统提示词。其核心职责包括：</p>
 * <ul>
 * <li>1. 协调多个 Agent 成员，并根据协作协议 (Protocol) 决定任务流转或终止。</li>
 * <li>2. 维护团队成员的名录及其职责上下文。</li>
 * <li>3. 强制执行严格的输出规范，确保主管仅作为决策路由存在。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamPromptProviderEn implements TeamPromptProvider {
    /**
     * 默认单例（使用标准主管模板）
     */
    private static final TeamPromptProviderEn _DEFAULT = new TeamPromptProviderEn(null, null);

    public static TeamPromptProviderEn getInstance() { return _DEFAULT; }

    private final Function<TeamTrace, String> roleProvider;
    private final Function<TeamTrace, String> instructionProvider;

    protected TeamPromptProviderEn(Function<TeamTrace, String> roleProvider,
                                   Function<TeamTrace, String> instructionProvider) {
        this.roleProvider = roleProvider;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() { return Locale.ENGLISH; }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色定义片段
        sb.append("## Role Definition\n").append(getRole(trace)).append("\n\n");

        // 2. 注入综合指令 (包含成员、上下文、协议及业务逻辑)
        sb.append(getInstruction(trace));

        return sb.toString();
    }

    @Override
    public String getRole(TeamTrace trace) {
        // 优先使用自定义角色提供者
        if (roleProvider != null) {
            return roleProvider.apply(trace);
        }
        return "You are the Team Supervisor, responsible for coordinating agents to complete the task";
    }

    @Override
    public String getInstruction(TeamTrace trace) {
        TeamConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 团队成员列表：动态注入当前团队中所有可用的 Agent 及其描述
        sb.append("### Team Members\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- **").append(name).append("**: ").append(agent.descriptionFor(trace.getContext())).append("\n");
        });

        // B. 任务上下文：明确当前需要解决的原始用户需求
        sb.append("\n### Current Task\n").append(trace.getPrompt().getUserContent()).append("\n");

        // C. 协作协议：由具体的协作策略（如 Sequential, Market 等）注入特定流转指令
        sb.append("\n### Collaboration Protocol\n");
        config.getProtocol().injectSupervisorInstruction(Locale.ENGLISH, sb);

        // D. 输出规范：严格约束主管的响应格式，确保下游解析器能准确识别下一步行动
        sb.append("\n### Output Specification\n")
                .append("1. **Progress Analysis**: Evaluate current progress and decide the next step.\n")
                .append("2. **Termination**: If the task is finished, output: ").append(config.getFinishMarker())
                .append(" followed by the final result.\n")
                .append("3. **Routing**: Otherwise, output **ONLY** the name of the next Agent to execute.\n");

        // E. 历史记录分析与准则：引导模型参考协作历史，防止死循环并确保决策质量
        sb.append("\n### History Analysis & Guidelines\n")
                .append("- Analyze the history. If it contains sufficient information, provide the final answer immediately.\n")
                .append("- Completion Signal: ").append(config.getFinishMarker()).append(".\n")
                .append("- Note: Avoid premature termination. Ensure all necessary experts have contributed.\n");

        // F. 增量业务指令注入：追加用户自定义的特定任务约束
        if (instructionProvider != null) {
            sb.append("\n### Core Task Instructions\n");
            sb.append(instructionProvider.apply(trace)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 创建提供者构建器
     */
    public static Builder builder() { return new Builder(); }

    /**
     * TeamPromptProviderEn 构建器类
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
        public TeamPromptProviderEn build() { return new TeamPromptProviderEn(roleProvider, instructionProvider); }
    }
}