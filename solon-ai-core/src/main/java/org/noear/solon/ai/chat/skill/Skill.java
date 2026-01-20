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
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 技能
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public interface Skill extends ToolProvider {
    /**
     * 名字
     */
    default String name() {
        return this.getClass().getSimpleName();
    }

    default String description(){
        return null;
    }

    /**
     * 技能元信息
     */
    default SkillMetadata metadata() {
        return new SkillMetadata(this.name(), this.description());
    }

    /**
     * 准入检查：决定该技能在当前环境下是否可用
     */
    default boolean isSupported(ChatPrompt prompt) {
        return true;
    }

    /**
     * 挂载钩子：技能被激活时触发，可用于初始化 Session
     */
    default void onAttach(ChatPrompt prompt) {
    }

    /**
     * 指令注入：转化并注入到 System Message
     */
    default String getInstruction(ChatPrompt prompt) {
        return null;
    }

    /**
     * 工具注入：转化并注入到工具列表
     */
    default Collection<FunctionTool> getTools() {
        return null;
    }


    /**
     * 注入指令
     */
    /**
     * 注入指令并对工具进行“染色”
     */
    default void injectInstruction(ChatPrompt prompt, StringBuilder combinedInstruction) {
        String ins = getInstruction(prompt);
        Collection<FunctionTool> tools = getTools();

        // 1. 如果有工具，进行元信息染色（借鉴 MCP 思想）
        if (tools != null && !tools.isEmpty()) {
            for (FunctionTool tool : tools) {
                // 将所属 Skill 的名字注入工具的 meta
                tool.metaPut("skill", name());
                // 如果需要，也可以把 Skill 的描述或其它元数据注入
                if (Utils.isNotEmpty(description())) {
                    tool.metaPut("skill_desc", description());
                }
            }
        }

        // 2. 构建 System Prompt 指令块
        if (Utils.isNotEmpty(ins) || (tools != null && !tools.isEmpty())) {
            if (combinedInstruction.length() > 0) {
                combinedInstruction.append("\n");
            }

            // 统一头部
            combinedInstruction.append("**Skill**: ").append(name());

            // 补充 Skill 描述（如果有）
            if (Utils.isNotEmpty(metadata().getDescription()) && !name().equals(metadata().getDescription())) {
                combinedInstruction.append(" (").append(metadata().getDescription()).append(")");
            }
            combinedInstruction.append("\n");

            // 注入具体指令
            if (Utils.isNotEmpty(ins)) {
                combinedInstruction.append(ins).append("\n");
            }

            // 注入工具关联说明（告知模型这些工具受此 Skill 指令约束）
            if (tools != null && !tools.isEmpty()) {
                String toolNames = tools.stream().map(t -> t.name()).collect(Collectors.joining(", "));
                combinedInstruction.append("- **Supported Tools**: ").append(toolNames).append("\n");
            }
        }
    }
}