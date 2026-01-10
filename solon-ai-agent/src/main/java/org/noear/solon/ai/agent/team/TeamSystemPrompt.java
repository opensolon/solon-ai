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

import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.function.Function;

/**
 * Team 协作模式系统提示词提供者
 *
 * <p>该接口负责为团队主管 (Supervisor) 构造其决策大脑的核心提示词（System Prompt）。</p>
 * <p>设计上采用分段构建模式，将管理逻辑与业务逻辑解耦：</p>
 * <ul>
 * <li><b>Role (身份)</b>: 定义主管的人格设定，决定决策的严谨度与管理风格。</li>
 * <li><b>Instruction (约束)</b>: 注入协作协议指令（如：任务指派逻辑、状态转换约束）及业务准则。</li>
 * <li><b>Dynamic Rendering</b>: 基于 Snel 模板引擎，支持在执行期从 {@link FlowContext} 动态注入业务变量。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamSystemPrompt {
    /**
     * 获取提示词关联的语言区域
     *
     * @return 默认返回 {@link Locale#CHINESE}
     */
    Locale getLocale();

    /**
     * 为当前上下文生成并渲染最终的系统提示词
     *
     * <p>执行流：{@link #getSystemPrompt(TeamTrace)} (获取模板) -> {@link SnelUtil#render} (变量填充)。</p>
     *
     * @param trace   协作轨迹，提供团队配置信息、成员名录及当前协作状态
     * @param context 流程上下文，用于渲染模板中的动态业务数据
     * @return 最终交付给 LLM 的完整提示词文本
     */
    default String getSystemPromptFor(TeamTrace trace, FlowContext context) {
        if (context == null) {
            return getSystemPrompt(trace);
        }

        return SnelUtil.render(getSystemPrompt(trace), context.model());
    }

    /**
     * 获取未渲染的原始系统提示词模板
     *
     * <p>通常由 {@link #getRole(TeamTrace)}、{@link #getInstruction(TeamTrace)} 以及协议特定的引导词组合而成。</p>
     *
     * @param trace 协作轨迹
     * @return 包含模板占位符的原始字符串
     */
    String getSystemPrompt(TeamTrace trace);

    /**
     * 获取角色定义片段
     *
     * <p>描述主管的职能身份。例如：“你是一个资深研发专家，负责协调多名开发与测试人员完成任务”。</p>
     *
     * @param trace 协作轨迹
     * @return 角色设定文案
     */
    String getRole(TeamTrace trace);

    /**
     * 获取核心指令片段
     *
     * <p>描述主管的行动逻辑。包含输出格式约束（如 JSON/XML）、协议指令（如指派逻辑）及特定的业务红线。</p>
     *
     * @param trace 协作轨迹
     * @return 指令约束文案
     */
    String getInstruction(TeamTrace trace);


    /**
     * 获取构建器实例
     */
    static Builder builder() {
        return TeamSystemPromptCn.builder();
    }

    /**
     * TeamSystemPrompt 构建器
     */
    static interface Builder {
        /**
         * 设置静态角色描述
         */
        default Builder role(String role) {
            return role(trace -> role);
        }

        /**
         * 设置静态业务指令
         */
        default Builder instruction(String instruction) {
            return instruction(trace -> instruction);
        }

        /**
         * 设置动态角色提供逻辑
         * <p>支持根据团队成员组成或当前协作深度动态调整主管的人格表现。</p>
         */
        Builder role(Function<TeamTrace, String> roleProvider);

        /**
         * 设置动态业务指令逻辑
         * <p>支持根据执行状态（如：重试次数、已消耗 Token）实时调整任务约束。</p>
         */
        Builder instruction(Function<TeamTrace, String> instructionProvider);

        /**
         * 构建系统提示词实例
         */
        TeamSystemPrompt build();
    }
}