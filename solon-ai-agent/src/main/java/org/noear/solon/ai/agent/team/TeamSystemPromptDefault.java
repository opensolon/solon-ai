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
package org.noear.solon.ai.agent.team;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.function.Function;

/**
 * 团队协作系统提示词实现（国际化版，语言由 {@code trace.getOptions().getLocale()} 决定）
 *
 * <p>核心职责：为团队主管 (Supervisor) 生成结构化的调度指令集，涵盖成员描述、任务目标、协作协议与输出约束。</p>
 * <p>文案来自资源包 {@code solon-ai-agent/i18n/team*.yml}（见 {@link AgentI18n}）。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public class TeamSystemPromptDefault implements TeamSystemPrompt {
    private static final TeamSystemPrompt _DEFAULT = new TeamSystemPromptDefault(null, null);

    public static TeamSystemPrompt getDefault() {
        return _DEFAULT;
    }

    private final String roleDesc;
    private final Function<TeamTrace, String> instructionProvider;

    protected TeamSystemPromptDefault(String roleDesc,
                                      Function<TeamTrace, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    /**
     * 渲染用语言（只由配置决定：options.locale 或默认语言）
     */
    protected Locale localeOf(TeamTrace trace) {
        return AgentI18n.resolve(trace.getOptions().getLocale());
    }

    /**
     * 构建完整的系统提示词模板
     */
    @Override
    public String getSystemPrompt(TeamTrace trace) {
        String role = getRole(trace);
        String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();
        // 1. 注入角色定义
        sb.append(AgentI18n.fmt(localeOf(trace), AgentI18nKeys.TEAM_PROMPT_ROLE, "role", role)).append("\n\n");
        // 2. 注入综合指令（成员、任务、协议、规范）
        sb.append(instruction);
        return sb.toString();
    }

    public String getRole(TeamTrace trace) {
        if (roleDesc != null) {
            return roleDesc;
        }

        if (trace.getOptions().getRole() != null) {
            return trace.getOptions().getRole();
        }

        return AgentI18n.get(localeOf(trace), AgentI18nKeys.TEAM_PROMPT_ROLE_DEFAULT);
    }

    /**
     * 核心指令构造逻辑：按维度拼装 Prompt 块
     */
    public String getInstruction(TeamTrace trace) {
        Locale locale = localeOf(trace);
        TeamAgentConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        // A. 团队成员名录：基于 Agent 职责描述与契约构建知识库
        sb.append(AgentI18n.get(locale, AgentI18nKeys.TEAM_PROMPT_MEMBERS_TITLE)).append("\n");
        sb.append("```json\n");
        ONode agentMetas = new ONode().asArray();
        config.getAgentMap().forEach((name, agent) -> {
            agentMetas.add(agent.toMetadata(trace.getContext()));
        });
        sb.append(agentMetas.toJson());
        sb.append("\n```\n");

        // B. 任务背景：注入用户原始 Prompt
        sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_PROMPT_TASK, "task", trace.getOriginalPrompt().getUserContent())).append("\n");

        // C. 协议规则：由协作协议（如 Swarm）注入特定的流转逻辑
        config.getProtocol().injectSupervisorInstruction(locale, sb);
        sb.append("\n");

        // D. 输出规范：强制约束回复格式，便于程序化解析决策
        sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_PROMPT_OUTPUT, "finishMarker", config.getFinishMarker())).append("\n");

        // E. 治理准则：防死循环与合规性约束
        sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_PROMPT_GUIDE, "finishMarker", config.getFinishMarker())).append("\n");

        // F. 增量指令：业务侧自定义的补充约束
        String instruction = null;
        if (instructionProvider != null) {
            instruction = instructionProvider.apply(trace);
        } else if (trace.getOptions().getInstruction() != null) {
            instruction = trace.getOptions().getInstruction();
        }

        if (instructionProvider != null || instruction != null) {
            sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.TEAM_PROMPT_INSTRUCTION_TITLE)).append("\n");
            // Agent 级指令
            sb.append(instruction).append("\n");
            sb.append("\n");
        }

        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements TeamSystemPrompt.Builder {
        protected String roleDesc;
        protected Function<TeamTrace, String> instructionProvider;

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
            return new TeamSystemPromptDefault(roleDesc, instructionProvider);
        }
    }
}
