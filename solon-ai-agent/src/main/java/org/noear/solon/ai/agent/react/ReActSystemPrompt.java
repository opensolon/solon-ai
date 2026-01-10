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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.function.Function;

/**
 * ReAct 提示词提供者
 *
 * <p>负责定义和组装 ReAct 智能体（Reasoning-Acting）的核心系统提示词。</p>
 * <p>通过该接口，可以将智能体的角色（Role）、特定指令（Instruction）以及上下文变量
 * 动态融合为 LLM 可理解的任务准则。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface ReActSystemPrompt {
    /**
     * 获取提示词关联的语言环境
     *
     * @return 默认为中文环境
     */
    default Locale getLocale() {
        return Locale.CHINESE;
    }

    /**
     * 获取最终渲染后的系统提示词
     *
     * <p>该方法会根据上下文（FlowContext）对 {@link #getSystemPrompt(ReActTrace)}
     * 返回的原始模板进行变量填充与渲染。</p>
     *
     * @param trace   执行过程溯源，提供当前步数、历史行为等元数据
     * @param context 流程上下文，用于提取动态业务变量
     * @return 最终发送给 LLM 的字符串
     */
    default String getSystemPromptFor(ReActTrace trace, FlowContext context) {
        return SnelUtil.render(getSystemPrompt(trace), context);
    }

    /**
     * 获取原始系统提示词模板
     * * @param trace 执行溯源
     * @return 包含 ReAct 格式约束（Thought/Action/Observation）的完整模板
     */
    String getSystemPrompt(ReActTrace trace);

    /**
     * 获取智能体角色定义
     * * @param trace 执行溯源
     * @return 描述智能体身份的人格设定
     */
    String getRole(ReActTrace trace);

    /**
     * 获取核心执行指令
     * * @param trace 执行溯源
     * @return 指导智能体如何处理任务、使用工具的具体约束
     */
    String getInstruction(ReActTrace trace);

    /// ///////////

    /**
     * 获取提示词构建器
     */
    static Builder builder() {
        return ReActSystemPromptCn.builder();
    }

    /**
     * ReAct 提示词构建器接口
     */
    static interface Builder {
        /**
         * 设置静态角色信息
         */
        default Builder role(String role) {
            return role(trace -> role);
        }

        /**
         * 设置静态指令信息
         */
        default Builder instruction(String instruction) {
            return instruction(trace -> instruction);
        }

        /**
         * 设置动态角色提供逻辑
         * <p>允许根据当前执行状态（步数、耗时等）动态切换角色感官。</p>
         */
        Builder role(Function<ReActTrace, String> roleProvider);

        /**
         * 设置动态指令提供逻辑
         * <p>允许根据溯源信息动态调整执行约束（例如：步数过多时引导收敛）。</p>
         */
        Builder instruction(Function<ReActTrace, String> instructionProvider);

        /**
         * 构建系统提示词实例
         */
        ReActSystemPrompt build();
    }
}