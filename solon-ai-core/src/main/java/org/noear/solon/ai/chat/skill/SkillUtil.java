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
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 *
 * @author noear
 * @since 3.8.4
 */
public class SkillUtil {
    private static final Logger LOG = LoggerFactory.getLogger(SkillUtil.class);

    /**
     * 激活技能
     */
    public static StringBuilder activeSkills(ModelOptionsAmend<?, ?> modelOptions, Prompt prompt, StringBuilder builder) {
        for (RankEntity<Skill> item : modelOptions.skills()) {
            Skill skill = item.target;

            try {
                if (skill.isSupported(prompt) == false) {
                    //不支持？跳过
                    continue;
                }
            } catch (Throwable e) {
                //出错？跳过
                LOG.error("Skill support check failed: {}", skill.getClass().getName(), e);
                continue;
            }

            try {
                // 开始挂载（可以做些初始化）
                skill.onAttach(prompt);
            } catch (Throwable e) {
                LOG.error("Skill active failed: {}", skill.getClass().getName(), e);
                throw e;
            }

            //聚合提示词
            injectSkillInstruction(skill, prompt, builder);

            //部署工具
            modelOptions.toolAdd(skill.getTools(prompt));
        }

        return builder;
    }


    /**
     * 注入指令并对工具进行“染色”
     */
    private static void injectSkillInstruction(Skill skill, Prompt prompt, StringBuilder combinedInstruction) {
        String ins = skill.getInstruction(prompt);
        Collection<FunctionTool> tools = skill.getTools(prompt);

        // 1. 如果有工具，进行元信息染色（借鉴 MCP 思想）
        if (Assert.isNotEmpty(tools)) {
            for (FunctionTool tool : tools) {
                // 将所属 Skill 的名字注入工具的 meta
                tool.metaPut("skill", skill.name());
            }
        }

        // 2. 构建 System Prompt 指令块
        if (Assert.isNotEmpty(ins) || Assert.isNotEmpty(tools)) {
            combinedInstruction.append("\n---\n"); // 使用分割线开启独立空间

            // 技能标题行：### [Skill: Name] Description
            combinedInstruction.append("### [Skill: ").append(skill.name()).append("]");
            if (Utils.isNotEmpty(skill.description())) {
                combinedInstruction.append(" - ").append(skill.description());
            }
            combinedInstruction.append("\n\n");

            // 注入技能特有的指令（如数据库结构、API 限制等）
            if (Assert.isNotEmpty(ins)) {
                combinedInstruction.append(ins.trim()).append("\n");
            }

            // 显式声明该技能控制的工具范围
            if (Assert.isNotEmpty(tools)) {
                String toolNames = tools.stream()
                        .map(t -> "`" + t.name() + "`") // 给工具名加反引号，增强识别度
                        .collect(Collectors.joining(", "));
                combinedInstruction.append("> **工具作用域**: 此技能指令适用于以下工具的调用: ").append(toolNames).append("\n");
            }

            combinedInstruction.append("---\n"); // 闭合分割线
        }
    }
}