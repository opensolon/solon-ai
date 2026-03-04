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

import org.noear.solon.ai.agent.AgentSystemPrompt;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.function.Function;

/**
 * 简单系统提示词 (System Prompt) 提供者
 * * <p>采用 [角色设定] + [执行指令] 的结构化布局，支持通过 Snel 引擎从 FlowContext 动态渲染变量</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SimpleSystemPrompt implements AgentSystemPrompt<SimpleTrace> {
    private static SimpleSystemPrompt _DEFAULT = new SimpleSystemPrompt(null, null);

    public static SimpleSystemPrompt getDefault() {
        return _DEFAULT;
    }

    /**
     * 角色设定提供者
     */
    private final String roleDesc;
    /**
     * 执行指令提供者
     */
    private final Function<SimpleTrace, String> instructionProvider;

    public SimpleSystemPrompt(String roleDesc, Function<SimpleTrace, String> instructionProvider) {
        this.roleDesc = roleDesc;
        this.instructionProvider = instructionProvider;
    }

    /**
     * 组合 角色 (Role) 与 指令 (Instruction) 文本
     */
    public String getSystemPrompt(SimpleTrace trace) {
        String role = getRole(trace);
        String instruction = getInstruction(trace);

        StringBuilder sb = new StringBuilder();

        if (Assert.isNotEmpty(role)) {
            sb.append("## 你的角色\n").append(role).append("。\n\n");
        }

        if (Assert.isNotEmpty(instruction)) {
            sb.append("## 执行指令\n").append(instruction);
        }

        return sb.toString();
    }

    /**
     * 获取角色文本
     */
    public String getRole(SimpleTrace trace) {
        if (roleDesc != null) {
            return roleDesc;
        }

        if (trace.getConfig().getRole() != null) {
            return trace.getConfig().getRole();
        }

        return null;
    }

    /**
     * 获取指令文本
     */
    public String getInstruction(SimpleTrace trace) {
        return instructionProvider != null ? instructionProvider.apply(trace) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 系统提示词构建器
     */
    public static class Builder {
        private String roleDesc;
        private Function<SimpleTrace, String> instructionProvider;

        /**
         * 设置静态角色文本
         */
        public Builder role(String role) {
            this.roleDesc = role;
            return this;
        }

        /**
         * 设置静态指令文本
         */
        public Builder instruction(String instruction) {
            return instruction(ctx -> instruction);
        }

        /**
         * 设置动态指令提供逻辑
         */
        public Builder instruction(Function<SimpleTrace, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        public SimpleSystemPrompt build() {
            return new SimpleSystemPrompt(roleDesc, instructionProvider);
        }
    }
}