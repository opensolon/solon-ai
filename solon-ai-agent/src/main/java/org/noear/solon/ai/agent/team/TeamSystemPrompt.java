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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * Team 协作模式提示词提供者
 *
 * <p>该接口负责为团队主管 (Supervisor) 生成系统提示词。采用分段构建模式：</p>
 * <ul>
 * <li><b>Role</b>: 定义主管的角色身份与管理风格。</li>
 * <li><b>Instruction</b>: 注入协议指令（如分发、汇总）及业务规则。</li>
 * <li><b>Template</b>: 支持 Snel 模板渲染，实现动态上下文注入。</li>
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
    default Locale getLocale() {
        return Locale.CHINESE;
    }

    /**
     * 为当前上下文生成最终渲染后的系统提示词
     *
     * @param trace   协作轨迹与配置信息
     * @param context 流程上下文（用于模板变量替换）
     * @return 渲染后的最终提示词字符串
     */
    default String getSystemPromptFor(TeamTrace trace, FlowContext context) {
        return SnelUtil.render(getSystemPrompt(trace), context);
    }

    /**
     * 获取原始系统提示词（通常由 Role 和 Instruction 组合而成）
     *
     * @param trace 协作轨迹
     */
    String getSystemPrompt(TeamTrace trace);

    /**
     * 获取角色定义
     * <p>例如：定义主管是一个“资深项目经理”或“技术评审专家”。</p>
     *
     * @param trace 协作轨迹
     */
    String getRole(TeamTrace trace);

    /**
     * 获取核心协作指令
     * <p>包含协议特定指令（如顺序执行规范）、任务约束、输出格式限制等。</p>
     *
     * @param trace 协作轨迹
     */
    String getInstruction(TeamTrace trace);
}