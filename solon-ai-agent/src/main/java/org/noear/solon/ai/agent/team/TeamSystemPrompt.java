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

import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.function.Function;

/**
 * 团队协作系统提示词提供者 (Team System Prompt Provider)
 *
 * <p>核心职责：为团队主管 (Supervisor) 构造决策大脑的 System Prompt。</p>
 * <p>采用解耦设计：角色设定 (Role) + 协议指令 (Instruction) + 动态渲染 (Snel Rendering)。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamSystemPrompt {
    /**
     * 获取提示词对应的语言环境
     */
    Locale getLocale();

    /**
     * 获取未渲染的原始提示词模板
     */
    String getSystemPrompt(TeamTrace trace);


    /**
     * 获取构建器实例
     */
    static Builder builder() {
        return TeamSystemPromptCn.builder();
    }

    /**
     * 提示词构建器接口
     */
    static interface Builder {
        /**
         * 设置角色设定
         */
        Builder role(String role);

        /**
         * 设置业务指令
         */
        default Builder instruction(String instruction) {
            return instruction(trace -> instruction);
        }

        /**
         * 设置动态指令提供逻辑
         */
        Builder instruction(Function<TeamTrace, String> instructionProvider);

        /**
         * 实例化系统提示词对象
         */
        TeamSystemPrompt build();
    }
}