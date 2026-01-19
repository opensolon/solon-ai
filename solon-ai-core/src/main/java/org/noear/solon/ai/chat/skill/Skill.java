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

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.lang.Preview;

import java.util.Collection;

/**
 * 技能
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public interface Skill {
    /**
     * 技能元信息
     */
    default SkillMetadata metadata() {
        return new SkillMetadata(this.getClass().getSimpleName());
    }

    /**
     * 准入检查：决定该技能在当前环境下是否可用
     */
    default boolean isSupported(Object ctx) {
        return true;
    }

    /**
     * 挂载钩子：技能被激活时触发，可用于初始化 Session
     */
    default void onAttach(Object ctx) {
    }

    /**
     * 指令注入：转化并注入到 System Message
     */
    default String getInstruction(Object ctx) {
        return null;
    }

    /**
     * 工具注入：转化并注入到工具列表
     */
    default Collection<FunctionTool> getTools() {
        return null;
    }
}