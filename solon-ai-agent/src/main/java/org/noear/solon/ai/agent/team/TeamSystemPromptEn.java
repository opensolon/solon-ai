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

import org.noear.snack4.ONode;
import org.noear.solon.lang.Preview;
import java.util.Locale;
import java.util.function.Function;

/**
 * 团队协作系统提示词实现（英文版）
 *
 * <p>核心职责：为主管 (Supervisor) 生成结构化的调度指令，包含角色定义、成员名录、协作协议及解析规范。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamSystemPromptEn implements TeamSystemPrompt {
    private static final TeamSystemPrompt _DEFAULT = new TeamSystemPromptEn(null, null);

    public static TeamSystemPrompt getDefault() {
        return _DEFAULT;
    }

    private final String roleDesc;
    private final Function<TeamTrace, String> instructionProvider;

    protected TeamSystemPromptEn(String roleDesc,
                                 Function<TeamTrace, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    @Override
    public Locale getLocale() {
        return Locale.ENGLISH;
    }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        String role = getRole(trace);
        String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        // 1. Role Section
        sb.append("## Your Role\n").append(role).append("\n\n");

        // 2. Comprehensive Instructions
        sb.append(instruction);

        return sb.toString();
    }

    public String getRole(TeamTrace trace) {
        if (roleDesc != null) {
            return roleDesc;
        }

        if (trace.getConfig().getRole() != null) {
            return trace.getConfig().getRole();
        }

        return "Team Supervisor, responsible for coordinating agents to complete the task";
    }

    /**
     * 构建核心指令集：采用 Markdown 分层结构提升 LLM 遵循度
     */
    public String getInstruction(TeamTrace trace) {
        TeamAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. Team Directory: 注入成员职责与契约 (Contract)
        sb.append("## Team Members\n");
        sb.append("```json\n");
        ONode agentMetas = new ONode().asArray();
        config.getAgentMap().forEach((name, agent) -> {
            agentMetas.add(agent.toMetadata(trace.getContext()));
        });
        sb.append(agentMetas.toJson());
        sb.append("\n```\n");

        // B. Context: 注入原始用户需求
        sb.append("\n## Current Task\n").append(trace.getOriginalPrompt().getUserContent()).append("\n");

        // C. Protocol: 注入特定协议（如 Sequential/A2A）的调度逻辑
        config.getProtocol().injectSupervisorInstruction(Locale.ENGLISH, sb);
        sb.append("\n");

        // D. Output Specification: 强制格式化响应，确保下游解析成功
        sb.append("\n## Output Specification\n")
                .append("1. **Termination**: If the task is finished, output: ").append(config.getFinishMarker())
                .append(" followed by the final result. If responding directly to the user, **DO NOT** include any analysis process.\n")
                .append("2. **Routing**: If the task is ongoing, output **ONLY** the name of the next Agent to execute. Do not provide extra text.\n");

        // E. Guidelines: 防止死循环及过度协作
        sb.append("\n## History Analysis & Guidelines\n")
                .append("- Reference collaboration history to avoid redundant turns.\n")
                .append("- Task Finish Marker: ").append(config.getFinishMarker()).append(".\n")
                .append("- Note: Do not terminate prematurely; ensure necessary expert input is obtained.\n");

        // F. Custom Business Logic: 注入增量业务指令
        if (instructionProvider != null) {
            sb.append("\n## Core Task Instructions\n");
            // Agent-level instructions
            sb.append(instructionProvider.apply(trace)).append("\n");
            sb.append("\n");
        }

        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements TeamSystemPrompt.Builder {
        private String roleDesc;
        private Function<TeamTrace, String> instructionProvider;

        public Builder role(String role) {
            this.roleDesc = role;
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
            return new TeamSystemPromptEn(roleDesc, instructionProvider);
        }
    }
}