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

import org.noear.solon.ai.agent.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * Team 协作模式系统提示词
 *
 * <p>该接口负责为团队主管 (Supervisor) 构造系统提示词 (System Prompt)。</p>
 * <p>采用分段构建模式，允许对不同维度的提示词进行解耦与定制：</p>
 * <ul>
 * <li><b>Role (角色)</b>: 确立主管的身份定位、专业背景及管理风格。</li>
 * <li><b>Instruction (指令)</b>: 注入协作协议（如任务指派、状态监控）及特定的业务准则。</li>
 * <li><b>Template (模板)</b>: 支持使用 Snel 模板引擎进行动态变量渲染。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface TeamSystemPrompt {
    /**
     * 获取语言区域（默认：中文）
     */
    Locale getLocale();

    /**
     * 为当前上下文生成并渲染最终的系统提示词
     *
     * @param trace   协作轨迹（包含配置与状态）
     * @param context 流程上下文（用于模板变量替换）
     * @return 渲染后的完整提示词字符串
     */
    default String getSystemPromptFor(TeamTrace trace, FlowContext context) {
        return SnelUtil.render(getSystemPrompt(trace), context);
    }

    /**
     * 获取原始系统提示词
     * <p>通常由 {@link #getRole(TeamTrace)} 与 {@link #getInstruction(TeamTrace)} 组合而成。</p>
     *
     * @param trace 协作轨迹
     */
    String getSystemPrompt(TeamTrace trace);

    /**
     * 获取角色定义片段
     * <p>描述主管是谁。例如：“你是一个资深软件架构师，负责评审团队的代码实现”。</p>
     *
     * @param trace 协作轨迹
     */
    String getRole(TeamTrace trace);

    /**
     * 获取核心指令片段
     * <p>描述主管如何行动。包含输出格式约束、协作协议指令（如顺序执行或竞争执行）等。</p>
     *
     * @param trace 协作轨迹
     */
    String getInstruction(TeamTrace trace);
}