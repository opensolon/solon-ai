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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 简单系统提示词 (System Prompt) 提供者
 * * <p>采用 [角色设定] + [执行指令] 的结构化布局，支持通过 Snel 引擎从 FlowContext 动态渲染变量</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SimpleSystemPrompt {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleSystemPrompt.class);

    /** 角色设定提供者 */
    private final String roleDesc;
    /** 执行指令提供者 */
    private final Function<FlowContext, String> instructionProvider;

    public SimpleSystemPrompt(String roleDesc, Function<FlowContext, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    /**
     * 获取最终渲染后的系统提示词
     */
    public String getSystemPromptFor(FlowContext context) {
        String rawPrompt = getSystemPrompt(context);
        if (context == null || rawPrompt == null) {
            return rawPrompt;
        }

        // 动态渲染模板（如解析 ${user_name}）
        String rendered = SnelUtil.render(rawPrompt, context.vars());

        if (LOG.isTraceEnabled()) {
            LOG.trace("Simple SystemPrompt rendered: {}", rendered);
        }

        return rendered;
    }

    /**
     * 组合 角色 (Role) 与 指令 (Instruction) 文本
     */
    public String getSystemPrompt(FlowContext context) {
        StringBuilder sb = new StringBuilder();
        String role = getRole();
        String inst = getInstruction(context);

        if (role != null) {
            sb.append("## 角色设定\n").append(role).append("\n\n");
        }
        if (inst != null) {
            sb.append("## 执行指令\n").append(inst);
        }
        return sb.toString();
    }

    /** 获取角色文本 */
    public String getRole() {
        return roleDesc != null ? roleDesc : null;
    }

    /** 获取指令文本 */
    public String getInstruction(FlowContext context) {
        return instructionProvider != null ? instructionProvider.apply(context) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 系统提示词构建器
     */
    public static class Builder {
        private String roleDesc;
        private Function<FlowContext, String> instructionProvider;

        /** 设置静态角色文本 */
        public Builder role(String role) {
            this.roleDesc = role;
            return this;
        }

        /** 设置静态指令文本 */
        public Builder instruction(String instruction) {
            return instruction(ctx -> instruction);
        }

        /** 设置动态指令提供逻辑 */
        public Builder instruction(Function<FlowContext, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        public SimpleSystemPrompt build() {
            return new SimpleSystemPrompt(roleDesc, instructionProvider);
        }
    }
}