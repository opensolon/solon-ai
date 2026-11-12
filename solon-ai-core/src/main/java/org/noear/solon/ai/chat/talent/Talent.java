/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.chat.talent;

import org.noear.solon.ai.AiSwitchable;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.lang.Preview;

import java.util.Collection;

/**
 * AI 才能接口
 * <p>
 * 才能是工具(Tools)、指令(Instruction)与元数据(Metadata)的聚合体。
 * 相比于裸工具，才能具备准入检查、指令增强及工具染色能力。
 * </p>
 *
 * @author noear
 * @since 3.8.4
 * @since 4.0.0
 */
@Preview("3.8.4")
public interface Talent extends AiSwitchable {
    /**
     * 获取才能名称（默认类名）
     */
    default String name() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取才能描述
     *
     * @deprecated 4.2.0 {@link #description(Prompt)}
     */
    @Deprecated
    default String description() {
        return null;
    }

    /**
     * 获取才能元信息（描述取 {@code description(null)}，即默认语言）
     */
    default TalentMetadata metadata() {
        return new TalentMetadata(this.name(), this.description(null));
    }

    /**
     * 按请求上下文获取才能描述（写入系统提示中的才能标题行）
     *
     * <p>实现方重写本方法即可；描述随请求语言变化时用 {@link Prompt#locale()} 取语言。
     * prompt 可能为 null（如构建 {@link #metadata()} 时），此时返回默认语言的描述。</p>
     *
     * @param prompt 当前提示词上下文（可能为 null）
     * @since 4.2
     */
    default String description(Prompt prompt) {
        return description();
    }

    /**
     * 准入检查：决定该才能在当前对话上下文中是否被激活
     *
     * @param prompt 当前提示词上下文
     * @return true 表示激活并挂载该才能
     */
    default boolean isSupported(Prompt prompt) {
        return true;
    }

    /**
     * 挂载钩子：才能被激活时触发
     * 可用于初始化会话状态、审计日志记录或上下文预处理
     */
    default void onAttach(Prompt prompt) {
    }

    /**
     * 动态指令注入：生成并注入到 System Message 的描述性文本（如果使用 MD 层级，建议从第2级开始）
     * 用于约束 AI 如何使用该才能下的工具
     *
     * <p>请求语言用 {@link Prompt#locale()} 获取（可能为 null，表示用默认语言）。</p>
     */
    default String getInstruction(Prompt prompt) {
        return null;
    }

    /**
     * 动态工具注入：获取该才能挂载的所有功能工具
     */
    default Collection<FunctionTool> getTools(Prompt prompt) {
        return null;
    }
}