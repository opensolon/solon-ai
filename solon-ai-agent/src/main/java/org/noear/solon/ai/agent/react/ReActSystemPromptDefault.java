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

import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Function;

/**
 * ReAct 模式提示词提供者（国际化版，语言由 {@code trace.getOptions().getLocale()} 决定）
 * <p>采用“协议+业务”增量构建：核心协议（Thought/Action/Observation）由内部强制注入，业务指令增量追加。</p>
 * <p>文案来自资源包 {@code solon-ai-agent/i18n/react*.yml}（见 {@link AgentI18n}）。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public class ReActSystemPromptDefault implements ReActSystemPrompt {
    private static final Logger log = LoggerFactory.getLogger(ReActSystemPromptDefault.class);

    private static final ReActSystemPrompt _DEFAULT = new ReActSystemPromptDefault(null, null);

    public static ReActSystemPrompt getDefault() {
        return _DEFAULT;
    }

    private final String roleDesc;
    private final Function<ReActTrace, String> instructionProvider;

    protected ReActSystemPromptDefault(String roleDesc,
                                       Function<ReActTrace, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    /**
     * 渲染用语言（只由配置决定：options.locale 或默认语言）
     */
    protected Locale localeOf(ReActTrace trace) {
        return AgentI18n.resolve(trace.getOptions().getLocale());
    }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        Locale locale = localeOf(trace);
        String role = getRole(trace);
        String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        // 1. 角色定义与 ReAct 范式宣告
        if (trace.getConfig().getStyle() == ReActStyle.STRUCTURED_TEXT) {
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PROMPT_INTRO_TEXT, "role", role));
        } else {
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PROMPT_INTRO_NATIVE, "role", role));
        }
        sb.append("\n\n");

        // 2. 注入指令集（含格式、准则、示例）
        sb.append(instruction);

        return sb.toString();
    }

    public String getRole(ReActTrace trace) {
        if (roleDesc != null) {
            return roleDesc;
        }

        if (trace.getOptions().getRole() != null) {
            return trace.getOptions().getRole();
        }

        return AgentI18n.get(localeOf(trace), AgentI18nKeys.REACT_PROMPT_ROLE_DEFAULT);
    }

    public String getInstruction(ReActTrace trace) {
        if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
            return getNaturalInstruction(trace);
        } else {
            return getClassicInstruction(trace);
        }
    }

    protected String getNaturalInstruction(ReActTrace trace) {
        Locale locale = localeOf(trace);
        StringBuilder sb = new StringBuilder();

        sb.append(AgentI18n.get(locale, AgentI18nKeys.REACT_PROMPT_NATIVE_RULES)).append("\n\n");

        // 业务指令注入
        appendBusinessInstructions(sb, trace);

        if (trace.getOptions().getTools().isEmpty()) {
            sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.REACT_PROMPT_NATIVE_NO_TOOLS)).append("\n");
        }

        return sb.toString();
    }

    protected String getClassicInstruction(ReActTrace trace) {
        Locale locale = localeOf(trace);
        String finishMarker = trace.getConfig().getFinishMarker();
        StringBuilder sb = new StringBuilder();

        // A. 格式约束：ReAct 循环的语法基石
        sb.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_FORMAT, "finishMarker", finishMarker)).append("\n\n");

        // B. 结束规格：确保任务能被系统正确识别截断
        sb.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_FINAL, "finishMarker", finishMarker)).append("\n\n");

        // C. 行为准则：防止模型出现伪造 Observation 或死循环
        sb.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_RULES, "finishMarker", finishMarker)).append("\n\n");

        // D. 业务指令注入
        appendBusinessInstructions(sb, trace);

        // E. 少样本引导 (Few-shot)
        sb.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_EXAMPLE, "finishMarker", finishMarker)).append("\n");

        // F. 工具集动态注入
        if (trace.getOptions().getTools().isEmpty()) {
            sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_NO_TOOLS)).append("\n");
        } else {
            String schemaLabel = " " + AgentI18n.get(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_TOOLS_SCHEMA) + " ";
            sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.REACT_PROMPT_CLASSIC_TOOLS_TITLE)).append("\n");
            trace.getOptions().getTools().forEach(t -> {
                sb.append("- ").append(t.name()).append(": ").append(t.descriptionAndMeta());
                // 必须告知模型参数 Schema 以便生成正确的 JSON
                if (Assert.isNotEmpty(t.inputSchema())) {
                    sb.append(schemaLabel).append(t.inputSchema());
                }
                sb.append("\n");
            });
        }

        return sb.toString();
    }

    protected void appendBusinessInstructions(StringBuilder sb, ReActTrace trace) {
        String instruction;
        if (instructionProvider != null) {
            instruction = instructionProvider.apply(trace);
        } else {
            instruction = trace.getOptions().getInstruction();
            if (instruction == null) {
                return;
            }
        }

        sb.append(AgentI18n.get(localeOf(trace), AgentI18nKeys.REACT_PROMPT_INSTRUCTION_TITLE)).append("\n");
        // Agent 级指令
        sb.append(instruction).append("\n");
        sb.append("\n");
    }

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements ReActSystemPrompt.Builder {
        protected String roleDesc;
        protected Function<ReActTrace, String> instructionProvider;

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
                log.debug("Building ReActSystemPromptDefault with custom role: {}, custom instruction: {}",
                        roleDesc != null, instructionProvider != null);
            }
            return new ReActSystemPromptDefault(roleDesc, instructionProvider);
        }
    }
}
