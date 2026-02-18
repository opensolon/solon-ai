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
package org.noear.solon.ai.chat.skill;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * AI 技能接口
 * <p>
 * 技能是工具(Tools)、指令(Instruction)与元数据(Metadata)的聚合体。
 * 相比于裸工具，技能具备准入检查、指令增强及工具染色能力。
 * </p>
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public interface Skill {
    /**
     * 获取技能名称（默认类名）
     */
    default String name() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取技能描述
     */
    default String description(){
        return null;
    }

    /**
     * 获取技能元信息
     */
    default SkillMetadata metadata() {
        return new SkillMetadata(this.name(), this.description());
    }

    /**
     * 准入检查：决定该技能在当前对话上下文中是否被激活
     *
     * @param prompt 当前提示词上下文
     * @return true 表示激活并挂载该技能
     */
    default boolean isSupported(Prompt prompt) {
        return true;
    }

    /**
     * 挂载钩子：技能被激活时触发
     * 可用于初始化会话状态、审计日志记录或上下文预处理
     */
    default void onAttach(Prompt prompt) {
    }

    /**
     * 动态指令注入：生成并注入到 System Message 的描述性文本（如果使用 MD 层级，从第四级开始）
     * 用于约束 AI 如何使用该技能下的工具
     */
    default String getInstruction(Prompt prompt) {
        return null;
    }

    /**
     * 动态工具注入：获取该技能挂载的所有功能工具
     */
    default Collection<FunctionTool> getTools(Prompt prompt) {
        return null;
    }
}