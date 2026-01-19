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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    static final Logger LOG = LoggerFactory.getLogger(TeamSystemPrompt.class);

    /**
     * 获取提示词对应的语言环境
     */
    Locale getLocale();

    /**
     * 为当前上下文生成并渲染最终的系统提示词
     * * @param trace   协作轨迹（包含成员与状态）
     *
     * @param context 流程上下文（提供渲染模版的业务变量）
     */
    default String getSystemPromptFor(TeamTrace trace, FlowContext context) {
        String template = getSystemPrompt(trace);

        if (context == null) {
            return template;
        }

        // 基于 Snel 模板引擎注入业务变量
        String finalPrompt = SnelUtil.render(template, context.vars());

        if (LOG.isTraceEnabled()) {
            LOG.trace("Team SystemPrompt rendered for agent [{}]:\n{}", trace.getAgentName(), finalPrompt);
        }

        return finalPrompt;
    }

    /**
     * 获取未渲染的原始提示词模板
     */
    String getSystemPrompt(TeamTrace trace);

    /**
     * 获取身份定义片段 (Role)
     * <p>描述 Supervisor 的人格设定与管理风格。</p>
     */
    String getRole();

    /**
     * 获取核心指令片段 (Instruction)
     * <p>描述行动逻辑、输出格式约束及协议指令。</p>
     */
    String getInstruction(TeamTrace trace);


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