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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Function;

/**
 * 团队协作系统提示词实现（中文版）
 *
 * <p>核心职责：为团队主管 (Supervisor) 生成结构化的调度指令集，涵盖成员描述、任务目标、协作协议与输出约束。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamSystemPromptCn implements TeamSystemPrompt {
    private static final Logger LOG = LoggerFactory.getLogger(TeamSystemPromptCn.class);

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
    public Locale getLocale() { return Locale.CHINESE; }

    /**
     * 构建完整的系统提示词模板
     */
    @Override
    public String getSystemPrompt(TeamTrace trace) {
        StringBuilder sb = new StringBuilder();
        // 1. 注入角色定义
        sb.append("## 角色定义\n").append(getRole(trace)).append("\n\n");
        // 2. 注入综合指令（成员、任务、协议、规范）
        sb.append(getInstruction(trace));
        return sb.toString();
    }

    @Override
    public String getRole(TeamTrace trace) {
        if (roleProvider != null) {
            return roleProvider.apply(trace);
        }
        return "你是一个团队协作主管 (Supervisor)，负责协调成员完成任务";
    }

    /**
     * 核心指令构造逻辑：按维度拼装 Prompt 块
     */
    @Override
    public String getInstruction(TeamTrace trace) {
        TeamAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 团队成员名录：基于 Agent 职责描述与契约构建知识库
        sb.append("## 团队成员\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- **").append(name).append("**:\n");
            sb.append("  - 职责: ").append(agent.descriptionFor(trace.getContext())).append("\n");

            AgentProfile profile = agent.profile();
            if (profile != null) {
                String info = profile.toFormatString(getLocale());
                if (info.length() > 0) {
                    sb.append("  - 契约: `").append(info).append("`\n");
                }
            }
        });

        // B. 任务背景：注入用户原始 Prompt
        sb.append("\n## 当前任务\n").append(trace.getPrompt().getUserContent()).append("\n");

        // C. 协议规则：由协作协议（如 Swarm）注入特定的流转逻辑
        config.getProtocol().injectSupervisorInstruction(Locale.CHINESE, sb);
        sb.append("\n");

        // D. 输出规范：强制约束回复格式，便于程序化解析决策
        sb.append("\n## 输出规范\n")
                .append("1. **状态分析**：分析当前执行进度，决定下一步行动。\n")
                .append("2. **任务终止**：如果任务已完成，必须输出: ").append(config.getFinishMarker())
                .append(" 并在其后提供最终答案。\n")
                .append("3. **继续执行**：若任务未完成，请**仅输出**下一个要执行的 Agent 名字，不要有额外文本。\n");

        // E. 治理准则：防死循环与合规性约束
        sb.append("\n## 历史分析与准则\n")
                .append("- 参考协作历史，避免重复执行相同逻辑。\n")
                .append("- 任务完成信号：").append(config.getFinishMarker()).append("。\n")
                .append("- 注意：严禁过早结束，确保专家意见已被充分获取。\n");

        // F. 增量指令：业务侧自定义的补充约束
        if (instructionProvider != null) {
            sb.append("\n## 核心任务指令\n");
            sb.append(instructionProvider.apply(trace)).append("\n");
        }

        return sb.toString();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder implements TeamSystemPrompt.Builder {
        private Function<TeamTrace, String> roleProvider;
        private Function<TeamTrace, String> instructionProvider;

        public Builder role(String role) {
            this.roleProvider = (t) -> role;
            return this;
        }

        public Builder role(Function<TeamTrace, String> roleProvider) {
            this.roleProvider = roleProvider;
            return this;
        }

        public Builder instruction(String instruction) {
            this.instructionProvider = (t) -> instruction;
            return this;
        }

        public Builder instruction(Function<TeamTrace, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        public TeamSystemPrompt build() {
            return new TeamSystemPromptCn(roleProvider, instructionProvider);
        }
    }
}